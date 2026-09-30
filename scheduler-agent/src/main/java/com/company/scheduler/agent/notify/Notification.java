package com.company.scheduler.agent.notify;

import java.time.Instant;
import java.util.UUID;

public record Notification(
        UUID id,
        UUID companyId,
        UUID eventId,
        UUID recipientMemberId,
        String channel,
        Category category,
        String title,
        String body,
        String aggregateType,
        UUID aggregateId,
        Instant readAt,
        Instant createdAt) {

    public enum Category { INFO, HIGH_RISK }

    public boolean read() { return readAt != null; }
}
