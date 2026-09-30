package com.company.scheduler.agent.audit;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 审计只插入；UPDATE/DELETE 由数据库触发器拒绝（见 agent 迁移）。 */
@Repository
public class AuditEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(AuditEvent event) {
        jdbc.update("""
                INSERT INTO audit_event(id, company_id, actor_type, actor_id, action,
                                        entity_type, entity_id, before_json, after_json, reason, request_id)
                VALUES (:id, :companyId, :actorType, :actorId, :action,
                        :entityType, :entityId, :beforeJson::jsonb, :afterJson::jsonb, :reason, :requestId)
                """, new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("companyId", event.companyId())
                .addValue("actorType", event.actorType())
                .addValue("actorId", event.actorId())
                .addValue("action", event.action())
                .addValue("entityType", event.entityType())
                .addValue("entityId", event.entityId())
                .addValue("beforeJson", event.beforeJson())
                .addValue("afterJson", event.afterJson())
                .addValue("reason", event.reason())
                .addValue("requestId", event.requestId()));
    }
}
