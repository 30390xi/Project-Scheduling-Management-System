package com.company.scheduler.task.store;

import com.company.scheduler.task.domain.RevisionSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 需求版本：原始输入与后续修改分别保存，只追加不覆盖。 */
@Repository
public class TaskRevisionRepository {
    private final JdbcTemplate jdbc;

    public TaskRevisionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record RevisionRow(UUID taskId, long revision, String contentJson, RevisionSource source,
                              UUID actorId, Instant createdAt) {}

    private static final RowMapper<RevisionRow> MAPPER = (rs, row) -> new RevisionRow(
            rs.getObject("task_id", UUID.class), rs.getLong("revision"),
            rs.getString("content_json"), RevisionSource.valueOf(rs.getString("source")),
            rs.getObject("actor_id", UUID.class), TaskRepository.instant(rs, "created_at"));

    /** 调用前须已锁定任务行。 */
    public long nextRevision(UUID companyId, UUID taskId) {
        Long next = jdbc.queryForObject("""
                SELECT coalesce(max(revision), 0) + 1 FROM task_revision WHERE company_id = ? AND task_id = ?
                """, Long.class, companyId, taskId);
        return next == null ? 1 : next;
    }

    public void insert(UUID companyId, UUID taskId, long revision, String contentJson,
                       RevisionSource source, UUID actorId) {
        jdbc.update("""
                INSERT INTO task_revision(company_id, task_id, revision, content_json, source, actor_id)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, companyId, taskId, revision, contentJson, source.name(), actorId);
    }

    public void deleteForTask(UUID companyId, UUID taskId) {
        jdbc.update("DELETE FROM task_revision WHERE company_id = ? AND task_id = ?", companyId, taskId);
    }

    public List<RevisionRow> listByTask(UUID companyId, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM task_revision WHERE company_id = ? AND task_id = ? ORDER BY revision
                """, MAPPER, companyId, taskId);
    }

    public Optional<RevisionRow> findLatest(UUID companyId, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM task_revision WHERE company_id = ? AND task_id = ? ORDER BY revision DESC LIMIT 1
                """, MAPPER, companyId, taskId).stream().findFirst();
    }
}
