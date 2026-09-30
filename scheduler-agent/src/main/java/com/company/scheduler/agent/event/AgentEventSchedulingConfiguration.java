package com.company.scheduler.agent.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox 后台调度。scheduler.agent.outbox.enabled=false 可停用轮询
 * （例如恢复流程中先冻结外部投递），分发器本身仍可手动调用。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "scheduler.agent.outbox", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentEventSchedulingConfiguration {
}
