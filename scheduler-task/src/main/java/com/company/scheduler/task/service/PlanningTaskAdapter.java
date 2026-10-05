package com.company.scheduler.task.service;

import com.company.scheduler.shared.contract.TaskPlanningPort;
import com.company.scheduler.shared.contract.TaskCatalog;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.store.*;
import com.company.scheduler.task.domain.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PlanningTaskAdapter implements TaskPlanningPort {
    private final TaskRepository tasks;
    private final TaskDependencyRepository dependencies;
    private final TaskSkillRepository skills;
    private final TaskAccessService access;
    private final CompanyClock clock;
    private final JdbcTemplate jdbc;
    public PlanningTaskAdapter(TaskRepository tasks, TaskDependencyRepository dependencies,
            TaskSkillRepository skills, TaskAccessService access, CompanyClock clock, JdbcTemplate jdbc) {
        this.tasks = tasks; this.dependencies = dependencies; this.skills = skills;
        this.access = access; this.clock = clock; this.jdbc = jdbc;
    }
    public List<Task> load(UUID actor, UUID company, List<UUID> roots) {
        access.requireMembership(actor, company);
        if (roots == null || roots.isEmpty() || roots.size() > 200) throw new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_TASK_SET", "请选择 1 至 200 个任务");
        Map<UUID, TaskRow> selected = new LinkedHashMap<>();
        for (UUID root : roots) {
            var task = tasks.find(company, root).orElseThrow(BusinessException::notFound);
            access.requireProjectAccess(actor, company, task.projectId());
            if (task.nodeType() == NodeType.LEAF) selected.put(task.id(), task);
            for (var row : tasks.listSubtree(company, root)) {
                if (row.nodeType() == NodeType.LEAF) selected.put(row.id(), row);
            }
        }
        // Replanning includes the downstream impact closure; unrelated tasks stay untouched.
        var pending = new ArrayDeque<>(selected.values());
        while (!pending.isEmpty()) {
            for (var next : dependencies.listSuccessors(company, pending.remove().id())) {
                if (selected.putIfAbsent(next.id(), next) == null) pending.add(next);
            }
            bounded(selected);
        }
        Set<UUID> movable = Set.copyOf(selected.keySet());
        pending.addAll(selected.values());
        while (!pending.isEmpty()) {
            for (var previous : dependencies.listPredecessors(company, pending.remove().id())) {
                if (selected.putIfAbsent(previous.id(), previous) == null) pending.add(previous);
            }
            bounded(selected);
        }
        var result = new ArrayList<Task>();
        for (var row : selected.values()) {
            access.requireProjectAccess(actor, company, row.projectId());
            LocalDate hard = null;
            TaskRow ancestor = row;
            for (int depth = 0; ancestor != null && depth < 4; depth++) {
                if (ancestor.dueType() == DueType.HARD && ancestor.dueDate() != null
                        && (hard == null || ancestor.dueDate().isBefore(hard))) hard = ancestor.dueDate();
                ancestor = ancestor.parentId() == null ? null : tasks.find(company, ancestor.parentId()).orElseThrow();
            }
            var predecessors = dependencies.listPredecessors(company, row.id()).stream().map(TaskRow::id).toList();
            var requirements = skills.listByTasks(company, List.of(row.id())).getOrDefault(row.id(), List.of())
                    .stream().filter(TaskSkillRepository.SkillRequirementRow::required)
                    .map(s -> new TaskCatalog.SkillRequirement(s.skillCode(), s.minLevel())).toList();
            boolean inProgress = row.status() == TaskStatus.IN_PROGRESS;
            Integer minutes = inProgress ? row.remainingMinutes() : row.estimatedMinutes();
            LocalDate earliest = row.earliestStartDate();
            if (inProgress) {
                LocalDate effective = jdbc.queryForObject("SELECT max(effective_after_date) FROM task_remaining_estimate WHERE company_id = ? AND task_id = ?",
                        LocalDate.class, company, row.id());
                LocalDate next = effective == null ? clock.today(company).plusDays(1) : effective.plusDays(1);
                if (earliest == null || next.isAfter(earliest)) earliest = next;
            }
            LocalDate completed = row.completedAt() == null ? null : row.completedAt().atZone(
                    java.time.ZoneId.of(jdbc.queryForObject("SELECT timezone FROM company WHERE id = ?", String.class, company))).toLocalDate();
            result.add(new Task(row.id(), row.projectId(), row.version(), row.title(), row.status().name(),
                    row.scheduleStatus().name(), minutes == null ? 0 : minutes, earliest, hard, completed,
                    row.fixedAssigneeId(), row.assigneeId(), row.preferredMemberId(), predecessors, requirements,
                    row.priority().name(), movable.contains(row.id())));
        }
        return List.copyOf(result);
    }
    private void bounded(Map<UUID, ?> tasks) {
        if (tasks.size() > 200) throw new BusinessException(HttpStatus.CONFLICT, "PLANNING_SCOPE_TOO_LARGE", "影响范围超过 200 个任务，请缩小范围");
    }
    public List<UUID> eligibleProjectMembers(UUID actor, UUID company, UUID project) {
        access.requireProjectAccess(actor, company, project);
        return jdbc.queryForList("""
            SELECT m.id FROM company_member m JOIN project_member p ON p.company_id = m.company_id AND p.member_id = m.id
            WHERE m.company_id = ? AND p.project_id = ? AND m.status = 'ACTIVE'
              AND m.accept_new_assignments AND m.accept_auto_assign ORDER BY m.id
            """, UUID.class, company, project);
    }
    public void requireManager(UUID actor, UUID company, UUID taskId) {
        access.requireMembership(actor, company);
        var task = tasks.find(company, taskId).orElseThrow(BusinessException::notFound);
        access.requireManager(access.requireProjectAccess(actor, company, task.projectId()));
    }
    public void assign(UUID actor, UUID company, UUID taskId, long version, UUID memberId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Plan commit requires transaction");
        var task = tasks.lockById(company, taskId).orElseThrow(BusinessException::notFound);
        access.requireManager(access.requireProjectAccess(actor, company, task.projectId()));
        if (task.version() != version) throw new BusinessException(HttpStatus.CONFLICT, "PLAN_STALE", "任务已变更，请重新模拟");
        if (!Set.of(TaskStatus.READY, TaskStatus.IN_PROGRESS).contains(task.status())) throw new BusinessException(HttpStatus.CONFLICT, "TASK_NOT_SCHEDULABLE", "任务不可排期");
        if (task.status() == TaskStatus.IN_PROGRESS && !Objects.equals(task.assigneeId(), memberId)) throw new BusinessException(HttpStatus.CONFLICT, "ASSIGNEE_FROZEN", "不能改变进行中任务负责人");
        jdbc.update("UPDATE task SET assignee_id = ?, schedule_status = 'PLANNED', version = version + 1, updated_at = now() WHERE company_id = ? AND id = ?",
                memberId, company, taskId);
    }
}
