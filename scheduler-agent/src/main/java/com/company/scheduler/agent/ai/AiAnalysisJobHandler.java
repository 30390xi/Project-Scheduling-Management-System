package com.company.scheduler.agent.ai;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.agent.audit.AuditEvent;
import com.company.scheduler.agent.audit.AuditRecorder;
import com.company.scheduler.agent.job.AgentJobProperties;
import com.company.scheduler.agent.job.AsyncJobRepository;
import com.company.scheduler.agent.job.ClaimedJob;
import com.company.scheduler.agent.job.JobHandler;
import com.company.scheduler.agent.job.JobTypes;
import com.company.scheduler.agent.job.PermanentJobFailure;
import com.company.scheduler.agent.notify.Notification;
import com.company.scheduler.agent.notify.NotificationService;
import com.company.scheduler.shared.contract.AiModelGateway;
import com.company.scheduler.shared.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI_ANALYSIS 作业编排（TRD 7.1 步骤 3-5 + 7.6）：
 * 输入去重复用 → 预算内调用模型 → 严格校验（一次结构修复）→ 保存建议版本 →
 * 审计与站内通知（经 Outbox）。所有副作用写入前先做租约 fencing 检查。
 * 模型不可用/未配置：明确失败 + 降级通知，提示人工估算路径，绝不伪造结果。
 */
@Component
public class AiAnalysisJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisJobHandler.class);

    private final AiAnalysisRunRepository runs;
    private final AsyncJobRepository jobs;
    private final AiModelGateway gateway;
    private final AiOutputValidator validator;
    private final QualityScoreCalculator qualityCalculator;
    private final EstimationService estimation;
    private final ConfirmationPolicy confirmationPolicy;
    private final AiGatewayProperties properties;
    private final AgentJobProperties jobProperties;
    private final NotificationService notifications;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AiAnalysisJobHandler(AiAnalysisRunRepository runs, AsyncJobRepository jobs,
            AiModelGateway gateway, AiOutputValidator validator, QualityScoreCalculator qualityCalculator,
            EstimationService estimation, ConfirmationPolicy confirmationPolicy,
            AiGatewayProperties properties, AgentJobProperties jobProperties,
            NotificationService notifications, AuditRecorder audit,
            TransactionTemplate transactions, Clock clock) {
        this.runs = runs;
        this.jobs = jobs;
        this.gateway = gateway;
        this.validator = validator;
        this.qualityCalculator = qualityCalculator;
        this.estimation = estimation;
        this.confirmationPolicy = confirmationPolicy;
        this.properties = properties;
        this.jobProperties = jobProperties;
        this.notifications = notifications;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public String jobType() {
        return JobTypes.AI_ANALYSIS;
    }

    @Override
    public void handle(ClaimedJob job) {
        UUID runId = UUID.fromString(AgentJson.toJsonNode(job.payloadJson()).path("runId").asText());
        AiAnalysisRun run = runs.findById(runId)
                .filter(found -> found.companyId().equals(job.companyId()))
                .orElseThrow(() -> new PermanentJobFailure("AI_RUN_NOT_FOUND",
                        "分析运行 " + runId + " 不存在"));

        // 幂等重入：上次已提交结果但作业未完成（如进程在 complete 前崩溃）
        if (run.terminal()) {
            log.info("run {} already terminal ({})", runId, run.status());
            return;
        }

        requireLease(job);

        // 相同输入复用既有成功结果，不重复付费调用
        var reusable = runs.findReusableSucceeded(run.companyId(), run.taskTextHash(),
                run.model(), run.promptVersion())
                .filter(candidate -> !candidate.id().equals(runId));
        if (reusable.isPresent()) {
            reuseResult(job, run, reusable.get());
            return;
        }

        if (!runs.markRunning(runId)) {
            throw new PermanentJobFailure("AI_RUN_STATE_UNEXPECTED", "运行 " + runId + " 无法进入执行态");
        }

        AiCallBudget budget = AiCallBudget.of(properties, clock);
        List<String> allowedSkills = allowedSkills(run.inputSnapshotJson());
        AiModelGateway.AnalysisRequest request = new AiModelGateway.AnalysisRequest(
                runId, run.companyId(), taskText(run.inputSnapshotJson()), allowedSkills);
        try {
            CallOutcome outcome = callWithTransientRetry(request, budget, null, null);
            AiValidationResult validation = validator.validate(outcome.result().structuredJson(),
                    allowedSkills);
            if (!validation.valid() && Boolean.TRUE.equals(properties.structureRepairEnabled())
                    && budget.hasCapacity()) {
                outcome = callWithTransientRetry(request, budget,
                        outcome.result().structuredJson(), validation.describe());
                validation = validator.validate(outcome.result().structuredJson(), allowedSkills);
            }
            if (!validation.valid()) {
                failRun(job, run, "AI_SCHEMA_INVALID", validation.describe());
                throw new PermanentJobFailure("AI_SCHEMA_INVALID",
                        "模型输出未通过结构化校验: " + validation.describe());
            }
            persistSuggestion(job, run, outcome, validation.output(), budget);
        } catch (AiCallBudget.AiBudgetExceededException budgetExceeded) {
            failRun(job, run, budgetExceeded.errorCode(), budgetExceeded.getMessage());
            throw new PermanentJobFailure(budgetExceeded.errorCode(), budgetExceeded.getMessage());
        } catch (AiGatewayAuthException authError) {
            failRun(job, run, "AI_AUTH_INVALID", authError.getMessage());
            notifications.publishInboxEvent(failureEventId(runId), run.companyId(),
                    Notification.Category.HIGH_RISK,
                    "AI 模型凭据无效",
                    "AI 分析运行 " + runId + " 因模型凭据被拒绝而失败，请管理员检查配置。",
                    List.of(run.submittedByMemberId()), "AI_ANALYSIS_RUN", runId);
            throw new PermanentJobFailure("AI_AUTH_INVALID", authError.getMessage());
        } catch (AiGatewayTransientException transientError) {
            // 作业级重试；耗尽尝试次数时把运行置为明确失败
            if (job.attempts() >= job.maxAttempts()) {
                failRun(job, run, "AI_PROVIDER_UNAVAILABLE", transientError.getMessage());
                notifications.publishInboxEvent(failureEventId(runId), run.companyId(),
                        Notification.Category.INFO,
                        "AI 分析暂时不可用，请人工估算",
                        "AI 分析运行 " + runId + " 重试后仍不可用。可在补充信息后重新提交分析，或直接人工填写工时与技能继续排期。",
                        List.of(run.submittedByMemberId()), "AI_ANALYSIS_RUN", runId);
            }
            throw transientError;
        } catch (BusinessException gatewayDisabled) {
            // 未配置/明确关闭：保留降级行为，提示人工估算路径
            failRun(job, run, gatewayDisabled.code(), gatewayDisabled.getMessage());
            notifications.publishInboxEvent(failureEventId(runId), run.companyId(),
                    Notification.Category.INFO,
                    "AI 分析已关闭，请人工估算",
                    "当前未启用 AI 模型服务（" + gatewayDisabled.code() + "）。任务可继续人工填写工时与技能后走规则排期。",
                    List.of(run.submittedByMemberId()), "AI_ANALYSIS_RUN", runId);
            throw new PermanentJobFailure(gatewayDisabled.code(), gatewayDisabled.getMessage());
        }
    }

    /** 副作用写入前的 fencing：租约不再属于本执行则放弃，等待回收或取消。 */
    private void requireLease(ClaimedJob job) {
        Instant extended = Instant.now(clock).plus(jobProperties.leaseDuration());
        if (!jobs.renewLease(job.id(), job.leaseOwner(), job.leaseGeneration(), extended)) {
            throw new PermanentJobFailure("JOB_LEASE_LOST",
                    "作业租约已失效，放弃本次执行结果");
        }
    }

    private record CallOutcome(AiModelGateway.AnalysisResult result, JsonNode usage) {}

    /**
     * 瞬时故障在预算内短退避重试；预算/截止时间由 AiCallBudget 强制。
     * repair 输入非空时走结构修复调用（仅 HTTP 网关支持）。
     */
    private CallOutcome callWithTransientRetry(AiModelGateway.AnalysisRequest request,
            AiCallBudget budget, String previousOutput, String violations) {
        AiGatewayTransientException lastError = null;
        while (true) {
            budget.consume();
            try {
                if (previousOutput != null && gateway instanceof HttpAiModelGateway httpGateway) {
                    var outcome = httpGateway.repairDetailed(request, previousOutput, violations);
                    return new CallOutcome(outcome.result(), outcome.usage());
                }
                if (gateway instanceof HttpAiModelGateway httpGateway) {
                    var outcome = httpGateway.analyzeDetailed(request);
                    return new CallOutcome(outcome.result(), outcome.usage());
                }
                return new CallOutcome(gateway.analyze(request), null);
            } catch (AiGatewayTransientException transientError) {
                lastError = transientError;
                if (!budget.hasCapacity()) {
                    break;
                }
                sleepBriefly();
            }
        }
        throw lastError;
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(Duration.ofMillis(400 + (long) (Math.random() * 800)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiGatewayTransientException("分析线程被中断");
        }
    }

    private void reuseResult(ClaimedJob job, AiAnalysisRun run, AiAnalysisRun reusable) {
        requireLease(job);
        transactions.executeWithoutResult(status -> {
            runs.markReused(run.id(), reusable.id());
            audit.record(run.companyId(), AuditEvent.ActorType.SYSTEM, null,
                    "AI_ANALYSIS_REUSED", "AI_ANALYSIS_RUN", run.id(),
                    null, AgentJson.toJson(Map.of("sourceRunId", reusable.id().toString())),
                    "相同输入复用既有成功结果", null);
            notifications.publishInboxEvent(completedEventId(run.id()), run.companyId(),
                    Notification.Category.INFO,
                    "AI 分析完成（复用既有结果）",
                    "AI 分析运行 " + run.id() + " 复用了相同输入的既有结果，请查看建议版本。",
                    List.of(run.submittedByMemberId()), "AI_ANALYSIS_RUN", run.id());
        });
    }

    private void persistSuggestion(ClaimedJob job, AiAnalysisRun run, CallOutcome outcome,
            AiAnalysisOutput output, AiCallBudget budget) {
        requireLease(job);
        QualityScoreCalculator.Components quality = qualityCalculator.evaluate(output, false);
        EstimationService.Computation computation = estimation.compute(output.estimate(),
                properties.bufferRatio());
        double ratio = output.estimate().optimisticMinutes() == null ? Double.MAX_VALUE
                : (double) output.estimate().pessimisticMinutes() / output.estimate().optimisticMinutes();
        var decision = confirmationPolicy.decide(ConfirmationPolicy.AdmissionInput.builder()
                .firstDecomposition(!output.subtasks().isEmpty())
                .infoComplete(output.missingInformation().isEmpty())
                .acceptanceCriteriaPresent(!output.acceptanceCriteria().isEmpty())
                .allKeyAssumptionsConfirmed(output.assumptions().stream().noneMatch(assumption ->
                        Boolean.TRUE.equals(assumption.confirmed())))
                .skillsMapped(!output.requiredSkills().isEmpty())
                .qualityScore(quality.weighted())
                .pessimisticOptimisticRatio(ratio)
                .build());

        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("output", output);
        suggestion.put("estimation", Map.of(
                "baseMinutes", computation.baseMinutes(),
                "plannedMinutesWithBuffer", computation.plannedMinutesWithBuffer()));
        suggestion.put("quality", Map.of(
                "score", round3(quality.weighted()),
                "components", Map.of(
                        "completeness", round3(quality.completeness()),
                        "caseEvidence", round3(quality.caseEvidence()),
                        "intervalStability", round3(quality.intervalStability()),
                        "modelSelfReport", round3(quality.modelSelfReport()),
                        "coldStart", quality.coldStart())));
        // D 模块可见的准入初判；项目自动派单开关、首次拆解与硬约束结论由集成后的确认入口合入
        suggestion.put("preliminaryAdmission", Map.of(
                "autoSubmitEligible", decision.autoSubmitAllowed(),
                "blockingReasons", decision.blockingReasons()));
        suggestion.put("humanConfirmationRequired", true);
        String suggestionJson = AgentJson.toJson(suggestion);

        transactions.executeWithoutResult(status -> {
            boolean stored = runs.markSucceeded(run.id(), outcome.result().structuredJson(),
                    suggestionJson, outcome.usage() == null ? null : outcome.usage().toString(),
                    round3(quality.weighted()), budget.used());
            if (!stored) {
                throw new PermanentJobFailure("AI_RUN_STATE_UNEXPECTED",
                        "运行 " + run.id() + " 已不是执行态，建议结果被拒绝");
            }
            audit.record(run.companyId(), AuditEvent.ActorType.SYSTEM, null,
                    "AI_ANALYSIS_SUCCEEDED", "AI_ANALYSIS_RUN", run.id(), null,
                    AgentJson.toJson(Map.of("providerCalls", budget.used(),
                            "qualityScore", round3(quality.weighted()))),
                    null, null);
            notifications.publishInboxEvent(completedEventId(run.id()), run.companyId(),
                    Notification.Category.INFO,
                    "AI 分析完成，请确认估算建议",
                    "AI 分析运行 " + run.id() + " 已生成建议版本（仅建议，需人工确认后才建立任务）。",
                    List.of(run.submittedByMemberId()), "AI_ANALYSIS_RUN", run.id());
        });
    }

    private void failRun(ClaimedJob job, AiAnalysisRun run, String errorCode, String detail) {
        requireLease(job);
        transactions.executeWithoutResult(status -> {
            runs.markFailed(run.id(), errorCode, detail);
            audit.record(run.companyId(), AuditEvent.ActorType.SYSTEM, null,
                    "AI_ANALYSIS_FAILED", "AI_ANALYSIS_RUN", run.id(), null,
                    AgentJson.toJson(Map.of("errorCode", errorCode)), null, null);
        });
    }

    private static UUID completedEventId(UUID runId) {
        return UUID.nameUUIDFromBytes(("ai-analysis-completed:" + runId).getBytes());
    }

    private static UUID failureEventId(UUID runId) {
        return UUID.nameUUIDFromBytes(("ai-analysis-failed:" + runId).getBytes());
    }

    private static String taskText(String inputSnapshotJson) {
        return AgentJson.toJsonNode(inputSnapshotJson).path("taskText").asText("");
    }

    private static List<String> allowedSkills(String inputSnapshotJson) {
        JsonNode skills = AgentJson.toJsonNode(inputSnapshotJson).path("allowedSkillCodes");
        if (!skills.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(skills.spliterator(), false)
                .map(JsonNode::asText).toList();
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
