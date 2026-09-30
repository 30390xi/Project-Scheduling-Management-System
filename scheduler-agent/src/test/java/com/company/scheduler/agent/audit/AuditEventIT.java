package com.company.scheduler.agent.audit;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.AgentIntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** 审计追加写：UPDATE/DELETE 被数据库触发器拒绝。 */
class AuditEventIT extends AgentIntegrationTestBase {

    @Autowired
    AuditRecorder recorder;
    @Autowired
    AuditEventRepository repository;

    @Test
    void recordsAuditTrailWithContext() {
        UUID entityId = UUID.randomUUID();
        recorder.record(companyId, AuditEvent.ActorType.USER, memberId,
                "PLAN_LOCKED", "TASK", entityId,
                null, "{\"lockedFields\":[\"ASSIGNEE\"]}", "负责人要求锁定人选", "req-42");
        Integer count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM audit_event WHERE company_id = ? AND entity_id = ? AND action = ?",
                Integer.class, companyId, entityId, "PLAN_LOCKED");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void auditHistoryRejectsUpdateAndDelete() {
        UUID entityId = UUID.randomUUID();
        recorder.record(companyId, AuditEvent.ActorType.SYSTEM, null,
                "CAPACITY_CHANGED", "MEMBER_DAY", entityId, "{\"capacity\":360}", "{\"capacity\":300}",
                "管理员调整容量", null);
        UUID auditId = jdbc.getJdbcTemplate().queryForObject(
                "SELECT id FROM audit_event WHERE entity_id = ? LIMIT 1", UUID.class, entityId);

        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update(
                "UPDATE audit_event SET reason = '篡改' WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update(
                "DELETE FROM audit_event WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void rejectsIncompleteAuditRecords() {
        assertThatThrownBy(() -> recorder.record(companyId, AuditEvent.ActorType.USER, memberId,
                "  ", "TASK", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
