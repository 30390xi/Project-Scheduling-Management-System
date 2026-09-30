package com.company.scheduler.task.store;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 任务技能要求。skill_code 在 C 的技能目录合并前按字符串校验格式，不做目录外键。 */
@Repository
public class TaskSkillRepository {
    private final JdbcTemplate jdbc;

    public TaskSkillRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record SkillRequirementRow(String skillCode, int minLevel, boolean required) {}

    private static final RowMapper<SkillRequirementRow> MAPPER = (rs, row) -> new SkillRequirementRow(
            rs.getString("skill_code"), rs.getInt("min_level"), rs.getBoolean("required"));

    public void replace(UUID companyId, UUID taskId, List<SkillRequirementRow> skills) {
        jdbc.update("DELETE FROM task_skill_requirement WHERE company_id = ? AND task_id = ?", companyId, taskId);
        for (SkillRequirementRow skill : skills) {
            jdbc.update("""
                    INSERT INTO task_skill_requirement(company_id, task_id, skill_code, min_level, required)
                    VALUES (?, ?, ?, ?, ?)
                    """, companyId, taskId, skill.skillCode(), skill.minLevel(), skill.required());
        }
    }

    public List<SkillRequirementRow> listByTask(UUID companyId, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM task_skill_requirement WHERE company_id = ? AND task_id = ? ORDER BY skill_code
                """, MAPPER, companyId, taskId);
    }

    public Map<UUID, List<SkillRequirementRow>> listByTasks(UUID companyId, Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        var placeholders = String.join(", ", taskIds.stream().map(id -> "?").toList());
        var params = new java.util.ArrayList<Object>();
        params.add(companyId);
        params.addAll(taskIds);
        var result = new HashMap<UUID, List<SkillRequirementRow>>();
        jdbc.query("SELECT * FROM task_skill_requirement WHERE company_id = ? AND task_id IN (" + placeholders + ")",
                rs -> {
                    SkillRequirementRow row = MAPPER.mapRow(rs, 0);
                    result.computeIfAbsent(rs.getObject("task_id", UUID.class), k -> new java.util.ArrayList<>())
                            .add(row);
                }, params.toArray());
        return result;
    }

    public void deleteForTask(UUID companyId, UUID taskId) {
        jdbc.update("DELETE FROM task_skill_requirement WHERE company_id = ? AND task_id = ?", companyId, taskId);
    }
}
