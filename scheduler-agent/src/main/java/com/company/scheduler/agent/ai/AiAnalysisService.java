package com.company.scheduler.agent.ai;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.agent.audit.AuditEvent;
import com.company.scheduler.agent.audit.AuditRecorder;
import com.company.scheduler.agent.job.AgentJobProperties;
import com.company.scheduler.agent.job.AsyncJobRepository;
import com.company.scheduler.agent.job.JobTypes;
import com.company.scheduler.shared.error.BusinessException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 分析提交入口。提交事务内同时写入运行记录与异步作业（可靠边界）：
 * 业务回滚则两者都不存在；幂等键重复提交返回原运行，不重复入队。
 * 分析结果只是建议版本，任务建立与容量占用由其他模块在人工确认后完成。
 */
@Service
public class AiAnalysisService {

    public record Submission(UUID runId, UUID jobId, boolean created) {}

    private final AiAnalysisRunRepository runs;
    private final AsyncJobRepository jobs;
    private final AgentJobProperties jobProperties;
    private final AiGatewayProperties properties;
    private final PromptSanitizer sanitizer;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    public AiAnalysisService(AiAnalysisRunRepository runs, AsyncJobRepository jobs,
            AgentJobProperties jobProperties, AiGatewayProperties properties,
            PromptSanitizer sanitizer, AuditRecorder audit, TransactionTemplate transactions) {
        this.runs = runs;
        this.jobs = jobs;
        this.jobProperties = jobProperties;
        this.properties = properties;
        this.sanitizer = sanitizer;
        this.audit = audit;
        this.transactions = transactions;
    }

    public Submission submit(UUID companyId, UUID submittedByMemberId, UUID taskAggregateId,
            String taskText, List<String> allowedSkillCodes, String idempotencyKey) {
        if (companyId == null || submittedByMemberId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AI_SUBMIT_CONTEXT_REQUIRED",
                    "缺少公司或提交人上下文");
        }
        if (taskText == null || taskText.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AI_TASK_TEXT_REQUIRED",
                    "任务正文不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AI_IDEMPOTENCY_KEY_REQUIRED",
                    "idempotencyKey（1..128 字符）必填");
        }
        List<String> skills = allowedSkillCodes == null ? List.of() : allowedSkillCodes.stream().distinct().sorted().toList();
        String model = properties.gatewayConfigured() ? properties.model() : DisabledAiModelGateway.MODEL;

        String requestHash = AiAnalysisRunRepository.hashInput(
                AgentJson.toJson(java.util.Arrays.asList(taskAggregateId, taskText, skills)), model,
                AiPrompts.PROMPT_VERSION);
        String scopedKey = AiAnalysisRunRepository.hashInput(idempotencyKey,
                submittedByMemberId.toString(), "analysis-submit");
        return transactions.execute(status -> {
            runs.lockSubmission(companyId, scopedKey);
            Optional<com.company.scheduler.agent.job.AsyncJob> existing =
                    jobs.findByKey(companyId, JobTypes.AI_ANALYSIS, scopedKey);
            if (existing.isPresent()) {
                UUID previousRunId = extractRunId(existing.get().payloadJson());
                var previous = runs.findById(previousRunId).orElseThrow();
                if (!requestHash.equals(AgentJson.toJsonNode(previous.inputSnapshotJson())
                        .path("requestHash").asText())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                            "幂等键已用于不同请求");
                }
                return new Submission(previousRunId, existing.get().id(), false);
            }
            String sanitized = sanitizer.sanitize(taskText);
            String cacheInput = AgentJson.toJson(java.util.Arrays.asList(sanitized, skills,
                    AiPrompts.SCHEMA_VERSION, properties.bufferRatio(),
                    properties.autoAssignQualityThreshold(), properties.maxPessimisticOptimisticRatio(),
                    properties.maxLeafTaskMinutes(), properties.maxSubtasks()));
            String hash = AiAnalysisRunRepository.hashInput(cacheInput, model, AiPrompts.PROMPT_VERSION);
            UUID runId = UUID.randomUUID();
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("taskText", sanitized);
            snapshot.put("allowedSkillCodes", skills);
            snapshot.put("requestHash", requestHash);
            runs.insert(new AiAnalysisRun(runId, companyId, null, submittedByMemberId, taskAggregateId,
                    hash, AgentJson.toJson(snapshot), model, AiPrompts.PROMPT_VERSION,
                    AiPrompts.SCHEMA_VERSION, "PENDING", 0, null, null, null, null, null, null,
                    null, null));
            String payload = "{\"runId\":\"" + runId + "\"}";
            var enqueued = jobs.enqueue(companyId, JobTypes.AI_ANALYSIS, runId, 0,
                    scopedKey, payload, jobProperties.defaultMaxAttempts());
            runs.attachJob(runId, enqueued.jobId());
            audit.record(companyId, AuditEvent.ActorType.USER, submittedByMemberId,
                    "AI_ANALYSIS_SUBMITTED", "AI_ANALYSIS_RUN", runId,
                    null, null, null, null);
            return new Submission(runId, enqueued.jobId(), true);
        });
    }

    /** 公司内查询运行状态；跨公司一律视为不存在。 */
    public Optional<AiAnalysisRun> load(UUID companyId, UUID runId) {
        return runs.findById(runId)
                .filter(run -> run.companyId().equals(companyId));
    }

    private UUID extractRunId(String payloadJson) {
        return UUID.fromString(AgentJson.toJsonNode(payloadJson).path("runId").asText());
    }
}
