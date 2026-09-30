package com.company.scheduler.task.store;

import com.company.scheduler.task.domain.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 状态变更留痕：追加写，不修改历史。 */
@Repository
public class TaskStatusEventRepository {
    private final JdbcTemplate jdbc;

    public TaskStatusEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record StatusEventRow(UUID id, UUID taskId, TaskStatus fromStatus, TaskStatus toStatus,
                                 UUID actorId, String reason, Instant createdAt) {}

    private static final RowMapper<StatusEventRow> MAPPER = (rs, row) -> new StatusEventRow(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
            rs.getString("from_status") == null ? null : TaskStatus.valueOf(rs.getString("from_status")),
            TaskStatus.valueOf(rs.getString("to_status")),
            rs.getObject("actor_id", UUID.class), rs.getString("reason"),
            TaskRepository.instant(rs, "created_at"));

    public void insert(UUID companyId, UUID taskId, TaskStatus from, TaskStatus to, UUID actorId, String reason) {
        jdbc.update("""
                INSERT INTO task_status_event(id, company_id, task_id, from_status, to_status, actor_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), companyId, taskId, from == null ? null : from.name(), to.name(), actorId, reason);
    }

    public List<StatusEventRow> listByTask(UUID companyId, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM task_status_event WHERE company_id = ? AND task_id = ? ORDER BY created_at, id
                """, MAPPER, companyId, taskId);
    }

    public void deleteForTask(UUID companyId, UUID taskId) {
        jdbc.update("DELETE FROM task_status_event WHERE company_id = ? AND task_id = ?", companyId, taskId);
    }
}
