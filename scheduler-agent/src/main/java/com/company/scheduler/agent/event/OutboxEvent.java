package com.company.scheduler.agent.event;

import java.time.Instant;
import java.util.UUID;

/** Outbox 事件领域视图。 */
public record OutboxEvent(
        UUID id,
        UUID companyId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String payloadJson,
        Instant createdAt,
        Instant publishedAt,
        int publishAttempts,
        Instant nextAttemptAt) {
}
