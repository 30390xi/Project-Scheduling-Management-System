package com.company.scheduler.task.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 实际工时流水：如实记录，不与计划占用重复扣容量。 */
@Repository
public class WorkLogRepository {
    private final JdbcTemplate jdbc;

    public WorkLogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record WorkLogRow(UUID id, UUID taskId, UUID memberId, LocalDate workDate, int minutes,
                             String note, String idempotencyKey, UUID createdBy, Instant createdAt) {}

    private static final RowMapper<WorkLogRow> MAPPER = (rs, row) -> new WorkLogRow(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
            rs.getObject("member_id", UUID.class), rs.getObject("work_date", LocalDate.class),
            rs.getInt("minutes"), rs.getString("note"), rs.getString("idempotency_key"),
            rs.getObject("created_by", UUID.class), TaskRepository.instant(rs, "created_at"));

    public void insert(UUID companyId, WorkLogRow log) {
        jdbc.update("""
                INSERT INTO work_log(id, company_id, task_id, member_id, work_date, minutes, note, idempotency_key, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, log.id(), companyId, log.taskId(), log.memberId(), log.workDate(), log.minutes(),
                log.note(), log.idempotencyKey(), log.createdBy());
    }

    public Optional<WorkLogRow> findByIdempotencyKey(UUID companyId, UUID taskId, UUID memberId, String key) {
        return jdbc.query("""
                SELECT * FROM work_log WHERE company_id = ? AND task_id = ? AND member_id = ? AND idempotency_key = ?
                """, MAPPER, companyId, taskId, memberId, key).stream().findFirst();
    }

    public List<WorkLogRow> listByTask(UUID companyId, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM work_log WHERE company_id = ? AND task_id = ? ORDER BY work_date, created_at, id
                """, MAPPER, companyId, taskId);
    }
}
