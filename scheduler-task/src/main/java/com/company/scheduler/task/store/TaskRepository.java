package com.company.scheduler.task.store;

import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskPriority;
import com.company.scheduler.task.domain.TaskStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {
    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static final RowMapper<TaskRow> MAPPER = (rs, row) -> map(rs);

    /** PostgreSQL JDBC 读取 timestamptz 走 OffsetDateTime 中转。 */
    static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static TaskRow map(ResultSet rs) throws SQLException {
        return new TaskRow(
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("parent_id", UUID.class),
                NodeType.valueOf(rs.getString("node_type")),
                rs.getObject("task_no", Long.class),
                TaskStatus.valueOf(rs.getString("status")),
                ScheduleStatus.valueOf(rs.getString("schedule_status")),
                rs.getString("blocked_from_status") == null ? null : TaskStatus.valueOf(rs.getString("blocked_from_status")),
                rs.getString("blocked_reason"),
                rs.getString("cancel_reason"),
                rs.getString("title"), rs.getString("description"), rs.getString("task_type"),
                TaskPriority.valueOf(rs.getString("priority")), DueType.valueOf(rs.getString("due_type")),
                rs.getObject("due_date", LocalDate.class), rs.getObject("earliest_start_date", LocalDate.class),
                rs.getObject("assignee_id", UUID.class), rs.getObject("fixed_assignee_id", UUID.class),
                rs.getObject("preferred_member_id", UUID.class),
                rs.getObject("estimated_minutes", Integer.class), rs.getObject("baseline_minutes", Integer.class),
                rs.getObject("remaining_minutes", Integer.class), rs.getInt("actual_minutes"),
                rs.getString("estimate_source"), rs.getString("acceptance_criteria"),
                instant(rs, "submitted_at"), instant(rs, "completed_at"), instant(rs, "canceled_at"),
                rs.getObject("created_by", UUID.class),
                instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    public void insert(TaskRow t, String idempotencyKey, String requestHash) {
        jdbc.update("""
                INSERT INTO task(id, company_id, project_id, parent_id, node_type, status, schedule_status,
                    title, description, task_type, priority, due_type, due_date, earliest_start_date,
                    fixed_assignee_id, preferred_member_id, estimated_minutes, estimate_source,
                    acceptance_criteria, idempotency_key, request_hash, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                t.id(), t.companyId(), t.projectId(), t.parentId(), t.nodeType().name(), t.status().name(),
                t.scheduleStatus().name(), t.title(), t.description(), t.taskType(), t.priority().name(),
                t.dueType().name(), t.dueDate(), t.earliestStartDate(), t.fixedAssigneeId(), t.preferredMemberId(),
                t.estimatedMinutes(), t.estimateSource(), t.acceptanceCriteria(), idempotencyKey, requestHash,
                t.createdBy());
    }

    public Optional<TaskRow> find(UUID companyId, UUID id) {
        return jdbc.query("SELECT * FROM task WHERE company_id = ? AND id = ?", MAPPER, companyId, id)
                .stream().findFirst();
    }

    /** 公司写锁内使用：锁定任务行，序列化并发修改。 */
    public Optional<TaskRow> lockById(UUID companyId, UUID id) {
        return jdbc.query("SELECT * FROM task WHERE company_id = ? AND id = ? FOR UPDATE", MAPPER, companyId, id)
                .stream().findFirst();
    }

    public Optional<TaskRow> findByIdempotencyKey(UUID companyId, UUID createdBy, String key) {
        return jdbc.query("SELECT * FROM task WHERE company_id = ? AND created_by = ? AND idempotency_key = ?",
                MAPPER, companyId, createdBy, key).stream().findFirst();
    }

    public String findRequestHash(UUID companyId, UUID id) {
        return jdbc.query("SELECT request_hash FROM task WHERE company_id = ? AND id = ?",
                rs -> rs.next() ? rs.getString("request_hash") : null, companyId, id);
    }

    public List<TaskRow> findByIds(UUID companyId, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
        var params = new ArrayList<Object>();
        params.add(companyId);
        params.addAll(ids);
        return jdbc.query("SELECT * FROM task WHERE company_id = ? AND id IN (" + placeholders + ")",
                MAPPER, params.toArray());
    }

    /** 提交成功，分配公司内稳定任务编号。 */
    public void markSubmitted(UUID companyId, UUID id, long taskNo) {
        jdbc.update("""
                UPDATE task SET status = 'SUBMITTED', task_no = ?, submitted_at = now(),
                    updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ?
                """, taskNo, companyId, id);
    }

    /** 公司内任务编号序列，同事务内原子递增。 */
    public long nextTaskNo(UUID companyId) {
        Long no = jdbc.queryForObject("""
                INSERT INTO task_sequence(company_id, next_value) VALUES (?, 2)
                ON CONFLICT (company_id) DO UPDATE SET next_value = task_sequence.next_value + 1
                RETURNING next_value - 1
                """, Long.class, companyId);
        if (no == null) throw new IllegalStateException("task_sequence 无返回");
        return no;
    }

    public void updateRequirement(UUID companyId, UUID id, String title, String description, String taskType,
                                  TaskPriority priority, DueType dueType, LocalDate dueDate, LocalDate earliestStartDate,
                                  UUID fixedAssigneeId, UUID preferredMemberId, Integer estimatedMinutes,
                                  String estimateSource, String acceptanceCriteria) {
        jdbc.update("""
                UPDATE task SET title = ?, description = ?, task_type = ?, priority = ?, due_type = ?, due_date = ?,
                    earliest_start_date = ?, fixed_assignee_id = ?, preferred_member_id = ?,
                    estimated_minutes = ?, estimate_source = ?, acceptance_criteria = ?,
                    updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ?
                """, title, description, taskType, priority.name(), dueType.name(), dueDate, earliestStartDate,
                fixedAssigneeId, preferredMemberId, estimatedMinutes, estimateSource, acceptanceCriteria,
                companyId, id);
    }

    /** 状态转移及副作用字段，由服务层基于已锁定行计算完整新状态。 */
    public void updateState(UUID companyId, UUID id, TaskStatus status, ScheduleStatus scheduleStatus,
                            TaskStatus blockedFrom, String blockedReason, Integer remainingMinutes,
                            Instant completedAt, Instant canceledAt, String cancelReason) {
        jdbc.update("""
                UPDATE task SET status = ?, schedule_status = ?, blocked_from_status = ?, blocked_reason = ?,
                    remaining_minutes = ?, completed_at = ?, canceled_at = ?, cancel_reason = ?,
                    updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ?
                """, status.name(), scheduleStatus.name(),
                blockedFrom == null ? null : blockedFrom.name(), blockedReason, remainingMinutes,
                toOffsetDateTime(completedAt), toOffsetDateTime(canceledAt), cancelReason, companyId, id);
    }

    /** PostgreSQL JDBC 写 timestamptz 使用 OffsetDateTime。 */
    static java.time.OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null
                : java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    /** 父任务状态按子任务聚合后回写。 */
    public void updateAggregateStatus(UUID companyId, UUID id, TaskStatus status, Instant completedAt) {
        jdbc.update("""
                UPDATE task SET status = ?, completed_at = ?, updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ?
                """, status.name(), toOffsetDateTime(completedAt), companyId, id);
    }

    public void promoteToParent(UUID companyId, UUID id) {
        jdbc.update("""
                UPDATE task SET node_type = 'PARENT', updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ?
                """, companyId, id);
    }

    public void setBaselineIfNull(UUID companyId, UUID id, int baselineMinutes) {
        jdbc.update("""
                UPDATE task SET baseline_minutes = ?, updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ? AND baseline_minutes IS NULL
                """, baselineMinutes, companyId, id);
    }

    public void addActualMinutes(UUID companyId, UUID id, int deltaMinutes) {
        jdbc.update("""
                UPDATE task SET actual_minutes = actual_minutes + ?, updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ?
                """, deltaMinutes, companyId, id);
    }

    public void updateRemainingMinutes(UUID companyId, UUID id, int remainingMinutes) {
        jdbc.update("""
                UPDATE task SET remaining_minutes = ?, updated_at = now(), version = version + 1
                WHERE company_id = ? AND id = ?
                """, remainingMinutes, companyId, id);
    }

    /** 不修改业务字段，仅推进版本与更新时间（如依赖边变更）。 */
    public void touch(UUID companyId, UUID id) {
        jdbc.update("UPDATE task SET updated_at = now(), version = version + 1 WHERE company_id = ? AND id = ?",
                companyId, id);
    }

    public List<TaskRow> listChildren(UUID companyId, UUID parentId) {
        return jdbc.query("""
                SELECT * FROM task WHERE company_id = ? AND parent_id = ? ORDER BY created_at, id
                """, MAPPER, companyId, parentId);
    }

    /** 递归子树（不含根自身），深度由建边规则限制在 3 以内。 */
    public List<TaskRow> listSubtree(UUID companyId, UUID rootId) {
        return jdbc.query("""
                WITH RECURSIVE sub AS (
                    SELECT * FROM task WHERE company_id = ? AND id = ?
                    UNION ALL
                    SELECT t.* FROM task t JOIN sub s
                      ON t.company_id = s.company_id AND t.parent_id = s.id
                ) SELECT * FROM sub WHERE id <> ? ORDER BY created_at, id
                """, MAPPER, companyId, rootId, rootId);
    }

    /** 祖先链深度：自身为 1。建边规则限制深度 ≤3，CTE 上限仅作数据异常保护。 */
    public int depthOf(UUID companyId, UUID id) {
        Integer depth = jdbc.queryForObject("""
                WITH RECURSIVE up AS (
                    SELECT parent_id, 1 AS lvl FROM task WHERE company_id = ? AND id = ?
                    UNION ALL
                    SELECT t.parent_id, up.lvl + 1 FROM task t JOIN up
                      ON t.company_id = ? AND t.id = up.parent_id
                     WHERE up.lvl < 10
                ) SELECT max(lvl) FROM up
                """, Integer.class, companyId, id, companyId);
        return depth == null ? 1 : depth;
    }

    /** 整棵树的根节点 id；无父即自身。 */
    public UUID rootOf(UUID companyId, UUID id) {
        UUID root = jdbc.query("""
                WITH RECURSIVE up AS (
                    SELECT id, parent_id, 1 AS lvl FROM task WHERE company_id = ? AND id = ?
                    UNION ALL
                    SELECT t.id, t.parent_id, up.lvl + 1 FROM task t JOIN up
                      ON t.company_id = ? AND t.id = up.parent_id
                     WHERE up.lvl < 10
                ) SELECT id FROM up WHERE parent_id IS NULL
                """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null, companyId, id, companyId);
        return root == null ? id : root;
    }

    /** 递归子树叶子计数（CTE 列须包含 join 所需的 company_id）。 */
    public int countLeavesUnder(UUID companyId, UUID rootId) {
        Integer count = jdbc.queryForObject("""
                WITH RECURSIVE sub AS (
                    SELECT id, company_id, node_type FROM task WHERE company_id = ? AND id = ?
                    UNION ALL
                    SELECT t.id, t.company_id, t.node_type FROM task t JOIN sub s
                      ON t.company_id = s.company_id AND t.parent_id = s.id
                ) SELECT count(*) FROM sub WHERE node_type = 'LEAF'
                """, Integer.class, companyId, rootId);
        return count == null ? 0 : count;
    }

    /** 游标列表：按 (updated_at DESC, id DESC) 稳定键集分页，仅返回成员可见项目。 */
    public List<TaskRow> listPage(UUID companyId, UUID memberId, UUID projectId, TaskStatus status,
                                  UUID assigneeId, UUID parentId, NodeType nodeType,
                                  Instant cursorUpdatedAt, UUID cursorId, int limit) {
        var sql = new StringBuilder("""
                SELECT * FROM task WHERE company_id = ?
                  AND project_id IN (SELECT project_id FROM project_member WHERE company_id = ? AND member_id = ?)
                """);
        var params = new ArrayList<Object>();
        params.add(companyId);
        params.add(companyId);
        params.add(memberId);
        if (projectId != null) { sql.append(" AND project_id = ?"); params.add(projectId); }
        if (status != null) { sql.append(" AND status = ?"); params.add(status.name()); }
        if (assigneeId != null) {
            sql.append(" AND (assignee_id = ? OR fixed_assignee_id = ?)");
            params.add(assigneeId);
            params.add(assigneeId);
        }
        if (parentId != null) { sql.append(" AND parent_id = ?"); params.add(parentId); }
        if (nodeType != null) { sql.append(" AND node_type = ?"); params.add(nodeType.name()); }
        if (cursorUpdatedAt != null) {
            sql.append(" AND (updated_at, id) < (?, ?)");
            params.add(toOffsetDateTime(cursorUpdatedAt));
            params.add(cursorId);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }

    /** 仅允许删除未提交草稿；依赖边由调用方先行清理。 */
    public void deleteDraft(UUID companyId, UUID id) {
        jdbc.update("DELETE FROM task WHERE company_id = ? AND id = ? AND status = 'DRAFT'", companyId, id);
    }
}
