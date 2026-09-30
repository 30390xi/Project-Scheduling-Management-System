package com.company.scheduler.agent.audit;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 审计追加写入口。加入调用者事务：业务成功才留痕；失败路径由调用方显式记录。
 * 不记录任务标题等用户输入，敏感字段不进入 before/after。
 */
@Service
public class AuditRecorder {

    private final AuditEventRepository repository;

    public AuditRecorder(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(UUID companyId, AuditEvent.ActorType actorType, UUID actorId,
            String action, String entityType, UUID entityId,
            String beforeJson, String afterJson, String reason, String requestId) {
        if (companyId == null || action == null || action.isBlank() || entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("audit event requires companyId, action and entityType");
        }
        repository.append(new AuditEvent(UUID.randomUUID(), companyId, actorType.name(), actorId,
                truncate(action, 64), truncate(entityType, 64), entityId,
                beforeJson, afterJson, truncate(reason, 500), truncate(requestId, 64),
                java.time.Instant.now()));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
