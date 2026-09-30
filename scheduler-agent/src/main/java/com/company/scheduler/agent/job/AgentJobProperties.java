package com.company.scheduler.agent.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** scheduler.agent.jobs.* 配置。默认值对应 TRD 13.1。 */
@ConfigurationProperties(prefix = "scheduler.agent.jobs")
public record AgentJobProperties(
        boolean enabled,
        Duration leaseDuration,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        int defaultMaxAttempts,
        String workerIdentity,
        int workerThreads,
        Duration pollInterval) {

    public AgentJobProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            leaseDuration = Duration.ofSeconds(300);
        }
        if (retryBaseDelay == null || retryBaseDelay.isNegative() || retryBaseDelay.isZero()) {
            retryBaseDelay = Duration.ofSeconds(10);
        }
        if (retryMaxDelay == null || retryMaxDelay.isNegative() || retryMaxDelay.isZero()) {
            retryMaxDelay = Duration.ofMinutes(10);
        }
        if (defaultMaxAttempts < 1 || defaultMaxAttempts > 10) {
            defaultMaxAttempts = 3;
        }
        if (workerIdentity == null || workerIdentity.isBlank()) {
            workerIdentity = "worker-" + ProcessHandle.current().pid();
        }
        if (workerThreads < 1) {
            workerThreads = 2;
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            pollInterval = Duration.ofSeconds(2);
        }
    }

    /** 仅供测试/默认场景使用的全默认实例；不能作为构造器，否则构造器绑定失效。 */
    public static AgentJobProperties defaults() {
        return new AgentJobProperties(true, null, null, null, 3, null, 2, null);
    }
}
