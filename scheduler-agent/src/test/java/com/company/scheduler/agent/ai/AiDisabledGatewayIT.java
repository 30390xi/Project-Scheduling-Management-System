package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.AgentIntegrationTestBase;
import com.company.scheduler.agent.event.OutboxDispatcher;
import com.company.scheduler.agent.job.AsyncJobRepository;
import com.company.scheduler.agent.job.AsyncJobStatus;
import com.company.scheduler.agent.job.JobRunner;
import com.company.scheduler.agent.notify.NotificationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** 未配置模型时的明确关闭/降级行为：失败可见、提示人工估算，绝无伪造结果。 */
class AiDisabledGatewayIT extends AgentIntegrationTestBase {

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
    @Qualifier("aiModelGateway")
    Object gateway;

    @Test
    void unconfiguredProviderKeepsExplicitDisabledBehavior() {
        assertThat(gateway).isInstanceOf(DisabledAiModelGateway.class);

        var submission = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "disabled-1");
        runner.pollOnce();

        var run = service.load(companyId, submission.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("AI_NOT_CONFIGURED");
        assertThat(run.validatedOutputJson()).as("关闭状态下不产生任何建议结果").isNull();

        var job = jobs.findById(submission.jobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(AsyncJobStatus.FAILED);
        assertThat(job.lastError()).contains("AI_NOT_CONFIGURED");

        // 降级通知：引导人工估算路径，而不是静默失败
        dispatcher.dispatchOnce(10);
        var inbox = notifications.listByMember(companyId, memberId, 10);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).title()).contains("人工估算");
        assertThat(inbox.get(0).category())
                .isEqualTo(com.company.scheduler.agent.notify.Notification.Category.INFO);
    }

    @Test
    void duplicateSubmitWhileDisabledStillDeduplicates() {
        var first = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "disabled-dup");
        var second = service.submit(companyId, memberId, UUID.randomUUID(),
                "实现登录接口，支持账号密码登录", AiSamples.SKILL_CATALOG, "disabled-dup");
        assertThat(second.created()).isFalse();
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(jobs.findByKey(companyId, "AI_ANALYSIS", "disabled-dup")).isPresent();
    }
}
