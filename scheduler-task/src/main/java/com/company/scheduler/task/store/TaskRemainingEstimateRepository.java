package com.company.scheduler.task.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 剩余工时确认：版本化保存，不从实际工时推算。 */
@Repository
public class TaskRemainingEstimateRepository {
    private final JdbcTemplate jdbc;

    public TaskRemainingEstimateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record RemainingRow(UUID id, UUID taskId, long version, LocalDate effectiveAfterDate,
                               int remainingMinutes, UUID confirmedBy, Instant createdAt) {}

    private static final RowMapper<RemainingRow> MAPPER = (rs, row) -> new RemainingRow(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getLong("version"),
            rs.getObject("effective_after_date", LocalDate.class), rs.getInt("remaining_minutes"),
            rs.getObject("confirmed_by", UUID.class), TaskRepository.instant(rs, "created_at"));

    /** 调用前须已锁定任务行。 */
    public long nextVersion(UUID companyId, UUID taskId) {
        Long next = jdbc.queryForObject("""
                SELECT coalesce(max(version), 0) + 1 FROM task_remaining_estimate WHERE company_id = ? AND task_id = ?
                """, Long.class, companyId, taskId);
        return next == null ? 1 : next;
    }

    public void insert(UUID companyId, UUID id, UUID taskId, long version, LocalDate effectiveAfterDate,
                       int remainingMinutes, UUID confirmedBy) {
        jdbc.update("""
                INSERT INTO task_remaining_estimate(id, company_id, task_id, version, effective_after_date, remaining_minutes, confirmed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, companyId, taskId, version, effectiveAfterDate, remainingMinutes, confirmedBy);
    }

    public List<RemainingRow> listByTask(UUID companyId, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM task_remaining_estimate WHERE company_id = ? AND task_id = ? ORDER BY version
                """, MAPPER, companyId, taskId);
    }
}
