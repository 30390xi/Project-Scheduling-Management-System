package com.company.scheduler.task.store;

import com.company.scheduler.task.domain.DependencyGraph;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskDependencyRepository {
    private final JdbcTemplate jdbc;

    public TaskDependencyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 整体替换某任务的前置集合；调用方须已完成无环校验。 */
    public void replacePredecessors(UUID companyId, UUID projectId, UUID successorId, List<UUID> predecessors) {
        jdbc.update("DELETE FROM task_dependency WHERE company_id = ? AND successor_id = ?", companyId, successorId);
        for (UUID predecessor : predecessors) {
            jdbc.update("""
                    INSERT INTO task_dependency(company_id, project_id, predecessor_id, successor_id)
                    VALUES (?, ?, ?, ?)
                    """, companyId, projectId, predecessor, successorId);
        }
    }

    /** 项目内全部 FS 依赖边，用于环路识别。 */
    public List<DependencyGraph.Edge> listEdgesByProject(UUID companyId, UUID projectId) {
        return jdbc.query("""
                SELECT predecessor_id, successor_id FROM task_dependency WHERE company_id = ? AND project_id = ?
                """, (rs, row) -> new DependencyGraph.Edge(
                        rs.getObject("predecessor_id", UUID.class), rs.getObject("successor_id", UUID.class)),
                companyId, projectId);
    }

    public List<TaskRow> listPredecessors(UUID companyId, UUID successorId) {
        return jdbc.query("""
                SELECT t.* FROM task_dependency d JOIN task t
                  ON t.company_id = d.company_id AND t.id = d.predecessor_id
                WHERE d.company_id = ? AND d.successor_id = ? ORDER BY t.created_at, t.id
                """, TaskRepository.MAPPER, companyId, successorId);
    }

    public List<TaskRow> listSuccessors(UUID companyId, UUID predecessorId) {
        return jdbc.query("""
                SELECT t.* FROM task_dependency d JOIN task t
                  ON t.company_id = d.company_id AND t.id = d.successor_id
                WHERE d.company_id = ? AND d.predecessor_id = ? ORDER BY t.created_at, t.id
                """, TaskRepository.MAPPER, companyId, predecessorId);
    }

    /** 批量：任务 → 前置 id 列表。 */
    public Map<UUID, List<UUID>> predecessorIdsOf(UUID companyId, Collection<UUID> successorIds) {
        if (successorIds.isEmpty()) return Map.of();
        var placeholders = String.join(", ", successorIds.stream().map(id -> "?").toList());
        var params = new java.util.ArrayList<Object>();
        params.add(companyId);
        params.addAll(successorIds);
        var result = new HashMap<UUID, List<UUID>>();
        jdbc.query("SELECT predecessor_id, successor_id FROM task_dependency WHERE company_id = ? AND successor_id IN ("
                        + placeholders + ")",
                rs -> {
                    result.computeIfAbsent(rs.getObject("successor_id", UUID.class), k -> new java.util.ArrayList<>())
                            .add(rs.getObject("predecessor_id", UUID.class));
                }, params.toArray());
        return result;
    }

    /** 删除草稿任务前清理其双向依赖边。 */
    public void deleteForTask(UUID companyId, UUID taskId) {
        jdbc.update("DELETE FROM task_dependency WHERE company_id = ? AND (predecessor_id = ? OR successor_id = ?)",
                companyId, taskId, taskId);
    }
}
