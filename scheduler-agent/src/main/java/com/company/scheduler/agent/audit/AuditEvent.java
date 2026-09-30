package com.company.scheduler.agent.audit;

import java.time.Instant;
import java.util.UUID;

/** 审计事件（追加写）。before/after 只存必要差异，不含附件正文或密钥。 */
public record AuditEvent(
        UUID id,
        UUID companyId,
        String actorType,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        String beforeJson,
        String afterJson,
        String reason,
        String requestId,
        Instant createdAt) {

    public enum ActorType { USER, SYSTEM, SERVICE }
}
