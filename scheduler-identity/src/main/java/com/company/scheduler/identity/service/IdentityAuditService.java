package com.company.scheduler.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 身份域高影响操作留痕：操作人、对象、原因、请求编号。统一审计汇聚由集成人协调。 */
@Service
public class IdentityAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdentityAuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(UUID companyId, UUID actorUserId, UUID actorMemberId,
                       String action, String entityType, UUID entityId, String reason, Map<String, ?> detail) {
        try {
            jdbc.update("""
                    INSERT INTO identity_audit_event
                        (id, company_id, actor_user_id, actor_member_id, action, entity_type, entity_id, reason, detail_json, request_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, UUID.randomUUID(), companyId, actorUserId, actorMemberId,
                    action, entityType, entityId, reason,
                    detail == null ? null : mapper.writeValueAsString(detail),
                    MDC.get("requestId"));
        } catch (Exception ex) {
            throw new IllegalStateException("审计事件写入失败，操作回滚", ex);
        }
    }
}
