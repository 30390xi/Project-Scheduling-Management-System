package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.AgentIntegrationTestBase;
import com.company.scheduler.agent.event.OutboxDispatcher;
import com.company.scheduler.agent.job.AsyncJobRepository;
import com.company.scheduler.agent.job.AsyncJobStatus;
import com.company.scheduler.agent.job.JobRunner;
import com.company.scheduler.agent.notify.NotificationRepository;
import com.company.scheduler.shared.contract.AiModelGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * AI 分析全链路（mock 模型 + 固定样本）：提交 → 作业执行 → 校验 → 建议版本 →
 * Outbox 通知；含幂等提交、结果复用、结构修复与最终失败的明确语义。
 */
@Import(AiAnalysisIT.ScriptedGatewayConfiguration.class)
class AiAnalysisIT extends AgentIntegrationTestBase {

    @Autowired
    AiAnalysisService service;
    @Autowired
    AsyncJobRepository jobs;
    @Autowired
    JobRunner runner;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    ScriptedModelGateway scriptedGateway;

    @BeforeEach
    void resetScript() {
        scriptedGateway.reset();
    }

    @Test
    void analyzesSubmitsJobOnceAndProducesSuggestionWithNotification() {
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF);
        UUID taskId = UUID.randomUUID();
        var submission = service.submit(companyId, memberId, taskId,
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "idem-analysis-1");

        assertThat(submission.created()).isTrue();
        var job = jobs.findById(submission.jobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(AsyncJobStatus.QUEUED);

        // 重复提交（网络重试场景）：同一幂等键返回原运行，不重复入队
        var retry = service.submit(companyId, memberId, taskId,
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "idem-analysis-1");
        assertThat(retry.created()).isFalse();
        assertThat(retry.runId()).isEqualTo(submission.runId());
        assertThat(scriptedGateway.calls.get()).isZero();

        runner.pollOnce();
        var run = service.load(companyId, submission.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.validatedOutputJson())
                .contains("\"plannedMinutesWithBuffer\": 300")
                .contains("\"humanConfirmationRequired\": true")
                .contains("\"blockingReasons\"");
        assertThat(run.providerCalls()).isEqualTo(1);
        // 假设保持未确认：模型不能替人确认
        assertThat(run.validatedOutputJson()).contains("\"confirmed\": false");

        assertThat(jobs.findById(submission.jobId()).orElseThrow().status())
                .isEqualTo(AsyncJobStatus.SUCCEEDED);

        dispatcher.dispatchOnce(10);
        var inbox = notifications.listByMember(companyId, memberId, 10);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).title()).contains("AI 分析完成");
    }

    @Test
    void sameInputReusesSucceededResultWithoutPayingAgain() {
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF);
        var first = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "reuse-1");
        runner.pollOnce();
        assertThat(service.load(companyId, first.runId()).orElseThrow().status())
                .isEqualTo("SUCCEEDED");

        // 相同正文、不同幂等键：应复用既有成功结果
        var second = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "reuse-2");
        assertThat(second.created()).isTrue();
        int callsAfterFirst = scriptedGateway.calls.get();
        runner.pollOnce();
        var run = service.load(companyId, second.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("REUSED");
        assertThat(scriptedGateway.calls.get()).as("复用不产生新的模型调用").isEqualTo(callsAfterFirst);
    }

    @Test
    void repairsInvalidStructureOnceAndSucceeds() {
        scriptedGateway.enqueue("{\"schemaVersion\":\"1.0\",\"unexpectedField\":true}");
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF);
        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "repair-1");
        runner.pollOnce();

        var run = service.load(companyId, submission.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.providerCalls()).isEqualTo(2);
        assertThat(jobs.findById(submission.jobId()).orElseThrow().status())
                .isEqualTo(AsyncJobStatus.SUCCEEDED);
    }

    @Test
    void failsExplicitlyWhenOutputStaysInvalid() {
        scriptedGateway.enqueue("not-json");
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF.replace("\"code\": \"JAVA\"", "\"code\": \"COBOL\""));
        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "invalid-1");
        runner.pollOnce();

        var run = service.load(companyId, submission.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("AI_SCHEMA_INVALID");
        assertThat(run.errorDetail()).contains("AI_SKILL_UNKNOWN");
        var job = jobs.findById(submission.jobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(AsyncJobStatus.FAILED);
        assertThat(job.lastError()).contains("AI_SCHEMA_INVALID");

        // 失败后不发送“完成”通知，只有明确失败路径；重复执行幂等（作业已终态）
        assertThat(runner.pollOnce()).isEmpty();
        assertThat(service.load(companyId, submission.runId()).orElseThrow().status())
                .isEqualTo("FAILED");
    }

    @Test
    void transientProviderFailureRetriesWithinRunBudgetAndSucceeds() {
        // TRD 7.6：短暂故障在运行预算内退避重试并计入调用数；预算未耗尽时不消耗作业重试
        scriptedGateway.enqueueTransientFailures(1);
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF);
        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "transient-1");
        runner.pollOnce();
        var run = service.load(companyId, submission.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.providerCalls()).isEqualTo(2);
        assertThat(jobs.findById(submission.jobId()).orElseThrow().status())
                .isEqualTo(AsyncJobStatus.SUCCEEDED);
    }

    @Test
    void submissionRejectsBlankTextAndBadIdempotencyKey() {
        assertThatThrownBy(() -> service.submit(companyId, memberId, null, "  ",
                AiSamples.SKILL_CATALOG, "bad-1"))
                .hasFieldOrPropertyWithValue("code", "AI_TASK_TEXT_REQUIRED");
        assertThatThrownBy(() -> service.submit(companyId, memberId, null, "正文",
                AiSamples.SKILL_CATALOG, ""))
                .hasFieldOrPropertyWithValue("code", "AI_IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void crossCompanyRunIsInvisible() {
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF);
        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "isolation-1");
        UUID otherCompany = UUID.randomUUID();
        jdbc.getJdbcTemplate().update("INSERT INTO company(id) VALUES (?)", otherCompany);
        assertThat(service.load(otherCompany, submission.runId())).isEmpty();
        assertThat(service.load(companyId, submission.runId())).isPresent();
    }

    @Test
    void reviewProviderBudgetSurvivesJobRetry() {
        scriptedGateway.enqueueTransientFailures(8);
        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "review retry budget", AiSamples.SKILL_CATALOG, "review-budget");
        runner.pollOnce();
        assertThat(scriptedGateway.calls.get()).isEqualTo(4);
        jdbc.getJdbcTemplate().update("UPDATE async_job SET next_run_at = now() - interval '1 second' WHERE id = ?",
                submission.jobId());
        runner.pollOnce();
        assertThat(scriptedGateway.calls.get())
                .as("A single analysis run must stay within its four-call budget across job retries")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void reviewUnconfirmedAssumptionMustBlockAdmission() {
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF);
        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "review unconfirmed assumptions", AiSamples.SKILL_CATALOG, "review-assumption");
        runner.pollOnce();
        assertThat(service.load(companyId, submission.runId()).orElseThrow().validatedOutputJson())
                .contains("UNCONFIRMED_KEY_ASSUMPTIONS");
    }

    @Test
    void reviewChangedSkillCatalogMustNotReuseOldSuggestion() {
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF);
        var first = service.submit(companyId, memberId, UUID.randomUUID(),
                "review changing skill catalog", AiSamples.SKILL_CATALOG, "review-cache-1");
        runner.pollOnce();
        assertThat(service.load(companyId, first.runId()).orElseThrow().status()).isEqualTo("SUCCEEDED");
        scriptedGateway.enqueue(AiSamples.VALID_SINGLE_LEAF.replace("JAVA", "PYTHON"));
        var second = service.submit(companyId, memberId, UUID.randomUUID(),
                "review changing skill catalog", List.of("PYTHON"), "review-cache-2");
        runner.pollOnce();
        assertThat(service.load(companyId, second.runId()).orElseThrow().validatedOutputJson())
                .as("A result from the old skill catalog must be revalidated against the current catalog")
                .doesNotContain("JAVA");
    }

    @Test
    void reviewIdempotencyKeyWithDifferentPayloadMustConflict() {
        UUID taskId = UUID.randomUUID();
        service.submit(companyId, memberId, taskId, "review task original", AiSamples.SKILL_CATALOG,
                "review-key-conflict");
        assertThatThrownBy(() -> service.submit(companyId, memberId, taskId,
                "review task completely changed", AiSamples.SKILL_CATALOG, "review-key-conflict"))
                .as("Same key with changed request must return an explicit conflict")
                .isInstanceOf(com.company.scheduler.shared.error.BusinessException.class);
    }

    @Test
    void resumedWorkerUsesPersistedBudgetAndAbsoluteDeadline() {
        scriptedGateway.script.add(new IllegalStateException("simulated interrupted worker"));
        scriptedGateway.enqueueTransientFailures(4);
        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "persisted budget after interruption", AiSamples.SKILL_CATALOG, "resumed-budget");
        runner.pollOnce();
        assertThat(service.load(companyId, submission.runId()).orElseThrow().providerCalls()).isEqualTo(1);
        var deadline = jdbc.getJdbcTemplate().queryForObject("SELECT run_deadline FROM ai_analysis_run WHERE id=?",
                java.sql.Timestamp.class, submission.runId());
        jdbc.getJdbcTemplate().update("UPDATE async_job SET next_run_at=now()-interval '1 second' WHERE id=?",submission.jobId());
        runner.pollOnce();
        var run = service.load(companyId, submission.runId()).orElseThrow();
        assertThat(run.providerCalls()).isEqualTo(4);
        assertThat(scriptedGateway.calls.get()).isEqualTo(4);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT run_deadline FROM ai_analysis_run WHERE id=?",
                java.sql.Timestamp.class, submission.runId())).isEqualTo(deadline);
    }

    private void awaitEligible(UUID jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!jobs.findById(jobId).orElseThrow().nextRunAt().isAfter(java.time.Instant.now())) {
                return;
            }
            Thread.sleep(50);
        }
        fail("job not eligible in time");
    }

    /** 测试替身：脚本化模型响应队列（固定样本），不依赖真实供应商。 */
    static class ScriptedModelGateway implements AiModelGateway {
        final ConcurrentLinkedQueue<Object> script = new ConcurrentLinkedQueue<>();
        final AtomicInteger calls = new AtomicInteger();

        void reset() {
            script.clear();
            calls.set(0);
        }

        void enqueue(String structuredJson) {
            script.add(structuredJson);
        }

        void enqueueTransientFailures(int count) {
            for (int i = 0; i < count; i++) {
                script.add(new AiGatewayTransientException("模拟 429"));
            }
        }

        @Override
        public AnalysisResult analyze(AnalysisRequest request) {
            calls.incrementAndGet();
            Object next = script.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            if (next instanceof String structuredJson) {
                return new AnalysisResult(AiPrompts.SCHEMA_VERSION, structuredJson, "scripted-model");
            }
            throw new IllegalStateException("script exhausted");
        }
    }

    @org.springframework.context.annotation.Configuration
    static class ScriptedGatewayConfiguration {
        @Bean
        AiModelGateway scriptedModelGateway() {
            return new ScriptedModelGateway();
        }
    }
}
