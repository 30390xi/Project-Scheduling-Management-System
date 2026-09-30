package com.company.scheduler.task.service;

import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.contract.TaskCatalog;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.store.TaskDependencyRepository;
import com.company.scheduler.task.store.TaskRepository;
import com.company.scheduler.task.store.TaskRow;
import com.company.scheduler.task.store.TaskSkillRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * TaskCatalog 读取端口（供 C 排期消费）。返回按真实已确认任务构建的快照：
 * 仅叶子、业务状态 READY/IN_PROGRESS/BLOCKED 的任务可排期；硬截止沿父链取最早。
 */
@Service
public class TaskCatalogService implements TaskCatalog {
    private final TaskRepository tasks;
    private final TaskDependencyRepository dependencies;
    private final TaskSkillRepository skills;
    private final CompanyAccess companyAccess;
    private final JdbcTemplate jdbc;

    public TaskCatalogService(TaskRepository tasks, TaskDependencyRepository dependencies,
                              TaskSkillRepository skills, CompanyAccess companyAccess, JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.skills = skills;
        this.companyAccess = companyAccess;
        this.jdbc = jdbc;
    }

    @Override
    public List<TaskSnapshot> loadSchedulable(UUID actorId, UUID companyId, List<UUID> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return List.of();
        var membership = companyAccess.requireMember(actorId, companyId);
        var distinctIds = new ArrayList<>(new LinkedHashSet<>(taskIds));
        Map<UUID, TaskRow> byId = new HashMap<>();
        for (TaskRow row : tasks.findByIds(companyId, distinctIds)) {
            byId.put(row.id(), row);
        }
        if (byId.size() != distinctIds.size()) {
            // 含不存在或跨公司任务：统一 404，不泄露存在性
            throw BusinessException.notFound();
        }
        // 逐项目校验调用者可见性，然后按父链补齐祖先用于硬截止继承
        checkProjectVisibility(companyId, membership.memberId(), byId.values());
        loadAncestors(companyId, byId);

        Map<UUID, List<UUID>> predecessors = dependencies.predecessorIdsOf(companyId, byId.keySet());
        Map<UUID, List<TaskSkillRepository.SkillRequirementRow>> skillMap =
                skills.listByTasks(companyId, byId.keySet());

        var snapshots = new ArrayList<TaskSnapshot>();
        for (UUID id : distinctIds) {
            TaskRow task = byId.get(id);
            if (task.nodeType() != NodeType.LEAF) continue;
            if (task.status() != TaskStatus.READY && task.status() != TaskStatus.IN_PROGRESS
                    && task.status() != TaskStatus.BLOCKED) {
                continue;
            }
            Integer estimated = task.status() == TaskStatus.IN_PROGRESS
                    ? coalesce(task.remainingMinutes(), task.estimatedMinutes())
                    : task.estimatedMinutes();
            if (estimated == null) continue;
            var requiredSkills = skillMap.getOrDefault(id, List.of()).stream()
                    .filter(TaskSkillRepository.SkillRequirementRow::required)
                    .map(s -> new SkillRequirement(s.skillCode(), s.minLevel()))
                    .toList();
            snapshots.add(new TaskSnapshot(task.id(), task.projectId(), task.version(), estimated,
                    task.earliestStartDate(), inheritedHardDeadline(task, byId), requiredSkills,
                    predecessors.getOrDefault(id, List.of()), task.fixedAssigneeId()));
        }
        return List.copyOf(snapshots);
    }

    private void checkProjectVisibility(UUID companyId, UUID memberId, java.util.Collection<TaskRow> rows) {
        var projectIds = new HashSet<UUID>();
        for (TaskRow row : rows) projectIds.add(row.projectId());
        for (UUID projectId : projectIds) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM project_member WHERE company_id = ? AND project_id = ? AND member_id = ?
                    """, Integer.class, companyId, projectId, memberId);
            if (count == null || count == 0) throw BusinessException.notFound();
        }
    }

    /** 拆解深度 ≤3，最多再向上取 3 层祖先。 */
    private void loadAncestors(UUID companyId, Map<UUID, TaskRow> byId) {
        for (int i = 0; i < 3; i++) {
            var missing = new HashSet<UUID>();
            for (TaskRow row : byId.values()) {
                UUID parentId = row.parentId();
                if (parentId != null && !byId.containsKey(parentId)) missing.add(parentId);
            }
            if (missing.isEmpty()) return;
            for (TaskRow parent : tasks.findByIds(companyId, missing)) {
                byId.put(parent.id(), parent);
            }
        }
    }

    /** 硬截止沿父链向叶子继承，取祖先与自身所有硬截止的最早日期（TRD 8.3）。 */
    private LocalDate inheritedHardDeadline(TaskRow task, Map<UUID, TaskRow> byId) {
        LocalDate deadline = null;
        TaskRow node = task;
        while (node != null) {
            if (node.dueType() == DueType.HARD && node.dueDate() != null) {
                if (deadline == null || node.dueDate().isBefore(deadline)) deadline = node.dueDate();
            }
            node = node.parentId() == null ? null : byId.get(node.parentId());
        }
        return deadline;
    }

    private static Integer coalesce(Integer first, Integer second) {
        return first != null ? first : second;
    }
}
