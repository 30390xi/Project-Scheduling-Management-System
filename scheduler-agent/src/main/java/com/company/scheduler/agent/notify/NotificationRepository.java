package com.company.scheduler.agent.notify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 站内通知。唯一键 (company_id,event_id,recipient_member_id,channel) 保证重投不重复。 */
@Repository
public class NotificationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true 表示新写入；false 表示该事件对该收件人已存在（幂等重投）。 */
    public boolean insertIgnored(UUID id, UUID companyId, UUID eventId, UUID recipientMemberId,
            String category, String title, String body, String aggregateType, UUID aggregateId) {
        return jdbc.update("""
                INSERT INTO notification(id, company_id, event_id, recipient_member_id, channel,
                                        category, title, body, aggregate_type, aggregate_id)
                VALUES (:id, :companyId, :eventId, :recipient, 'INBOX', :category, :title, :body,
                        :aggregateType, :aggregateId)
                ON CONFLICT (company_id, event_id, recipient_member_id, channel) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("companyId", companyId)
                .addValue("eventId", eventId)
                .addValue("recipient", recipientMemberId)
                .addValue("category", category)
                .addValue("title", title)
                .addValue("body", body)
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)) == 1;
    }

    public List<Notification> listByMember(UUID companyId, UUID memberMemberId, int limit) {
        return jdbc.query("""
                SELECT * FROM notification
                WHERE company_id = :companyId AND recipient_member_id = :recipient
                ORDER BY created_at DESC, id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("recipient", memberMemberId)
                .addValue("limit", Math.min(Math.max(limit, 1), 100)),
                (rs, i) -> new Notification(
                        rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("recipient_member_id", UUID.class),
                        rs.getString("channel"),
                        Notification.Category.valueOf(rs.getString("category")),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public Optional<Notification> findOwned(UUID companyId, UUID notificationId, UUID recipientMemberId) {
        List<Notification> found = jdbc.query("""
                SELECT * FROM notification
                WHERE company_id = :companyId AND id = :id AND recipient_member_id = :recipient
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("id", notificationId)
                .addValue("recipient", recipientMemberId),
                (rs, i) -> new Notification(
                        rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("recipient_member_id", UUID.class),
                        rs.getString("channel"),
                        Notification.Category.valueOf(rs.getString("category")),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public boolean markRead(UUID companyId, UUID notificationId, UUID recipientMemberId, Instant now) {
        return jdbc.update("""
                UPDATE notification SET read_at = :now
                WHERE company_id = :companyId AND id = :id AND recipient_member_id = :recipient
                      AND read_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("companyId", companyId)
                .addValue("id", notificationId)
                .addValue("recipient", recipientMemberId)) == 1;
    }

    public long countByEvent(UUID companyId, UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE company_id = :companyId AND event_id = :eventId",
                new MapSqlParameterSource()
                        .addValue("companyId", companyId)
                        .addValue("eventId", eventId), Long.class);
        return count == null ? 0 : count;
    }
}
