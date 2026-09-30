package com.company.scheduler.agent.event;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 事务 Outbox。append 必须在业务事务内调用：业务回滚，事件一并消失；
 * 分发器在事务外至少一次消费，由 processed_event 与通知唯一键去重。
 */
@Repository
public class OutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等追加：同 id（调用方用确定性 UUID）重复插入被忽略。返回是否真正写入。 */
    public boolean append(UUID eventId, UUID companyId, String eventType,
            String aggregateType, UUID aggregateId, String payloadJson) {
        return jdbc.update("""
                INSERT INTO outbox_event(id, company_id, event_type, aggregate_type, aggregate_id, payload)
                VALUES (:id, :companyId, :eventType, :aggregateType, :aggregateId, :payload::jsonb)
                ON CONFLICT (id) DO NOTHING
                """, params(eventId, companyId, eventType, aggregateType, aggregateId, payloadJson)) == 1;
    }

    /**
     * 领取一批待发布事件：SKIP LOCKED 避免多实例重复分发；
     * 领取即递增 attempts 并预约下一次尝试时间，防止崩溃后事件卡死。
     */
    public List<OutboxEvent> claimBatch(Instant now, Instant nextAttemptOnFailure, int limit) {
        return jdbc.query("""
                UPDATE outbox_event SET
                    publish_attempts = publish_attempts + 1,
                    next_attempt_at = :nextAttempt
                WHERE id IN (
                    SELECT id FROM outbox_event
                    WHERE published_at IS NULL AND next_attempt_at <= :now
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                RETURNING id, company_id, event_type, aggregate_type, aggregate_id, payload,
                          created_at, published_at, publish_attempts, next_attempt_at
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("nextAttempt", Timestamp.from(nextAttemptOnFailure))
                .addValue("limit", limit),
                (rs, i) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("published_at") == null ? null
                                : rs.getTimestamp("published_at").toInstant(),
                        rs.getInt("publish_attempts"),
                        rs.getTimestamp("next_attempt_at").toInstant()));
    }

    /** 所有消费者成功处理后标记发布完成；崩溃导致漏标时，重投由 processed_event 去重。 */
    public boolean markPublished(UUID eventId) {
        return jdbc.update("""
                UPDATE outbox_event SET published_at = now(), next_attempt_at = now()
                WHERE id = :id AND published_at IS NULL
                """, new MapSqlParameterSource("id", eventId)) == 1;
    }

    /** 部分消费者失败：事件保持未发布，按退避重投。 */
    public boolean scheduleRetry(UUID eventId, Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE outbox_event SET next_attempt_at = :nextAttempt
                WHERE id = :id AND published_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("nextAttempt", Timestamp.from(nextAttemptAt))) == 1;
    }

    public long countPending() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    private MapSqlParameterSource params(UUID eventId, UUID companyId, String eventType,
            String aggregateType, UUID aggregateId, String payloadJson) {
        return new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("companyId", companyId)
                .addValue("eventType", eventType)
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("payload", payloadJson);
    }
}
