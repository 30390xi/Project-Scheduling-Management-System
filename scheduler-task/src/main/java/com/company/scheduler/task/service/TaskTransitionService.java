package com.company.scheduler.task.service;

import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.api.dto.TransitionRequest;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.RevisionSource;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.domain.TaskTransitions;
import com.company.scheduler.task.store.TaskDependencyRepository;
import com.company.scheduler.task.store.TaskRemainingEstimateRepository;
import com.company.scheduler.task.store.TaskRepository;
import com.company.scheduler.task.store.TaskRevisionRepository;
import com.company.scheduler.task.store.TaskRow;
import com.company.scheduler.task.store.TaskStatusEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务状态转移：开始、阻塞、解除、送验、退回、验收、取消、重开。
 * 锁顺序遵循 TRD 9.2：先公司 guard，再任务行。完成/取消的容量释放由 C 消费任务事件处理，
 * 本模块不直接修改容量表。
 */
@Service
public class TaskTransitionService {
    private final TaskRepository tasks;
    private final TaskDependencyRepository dependencies;
    private final TaskStatusEventRepository events;
    private final TaskRevisionRepository revisions;
    private final TaskRemainingEstimateRepository remainingEstimates;
    private final TaskAccessService accessService;
    private final PlanningGuardService guard;
    private final CompanyClock clock;
    private final TaskService taskService;
    private final ObjectMapper mapper;
    private final com.company.scheduler.shared.contract.AllocationLifecycle allocations;

    public TaskTransitionService(TaskRepository tasks, TaskDependencyRepository dependencies,
                                 TaskStatusEventRepository events, TaskRevisionRepository revisions,
                                 TaskRemainingEstimateRepository remainingEstimates,
                                 TaskAccessService accessService, PlanningGuardService guard,
                                 CompanyClock clock, TaskService taskService, ObjectMapper mapper,
                                 com.company.scheduler.shared.contract.AllocationLifecycle allocations) {
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.events = events;
        this.revisions = revisions;
        this.remainingEstimates = remainingEstimates;
        this.accessService = accessService;
        this.guard = guard;
        this.clock = clock;
        this.taskService = taskService;
        this.mapper = mapper;
        this.allocations = allocations;
    }

    @Transactional
    public TaskDetail transition(UUID userId, UUID companyId, UUID taskId, TransitionRequest req,
                                 long expectedVersion) {
        accessService.requireMembership(userId, companyId);
        guard.lockAndBump(companyId);
        TaskRow task = taskService.lockAndCheckVersion(companyId, taskId, expectedVersion);
        var access = accessService.requireProjectAccess(userId, companyId, task.projectId());
        requireActionPermission(task, req, access);

        boolean predecessorsDone = req.action() == com.company.scheduler.task.domain.TransitionAction.START
                && dependencies.listPredecessors(companyId, taskId).stream()
                        .allMatch(p -> p.status() == TaskStatus.DONE);
        TaskStatus newStatus = TaskTransitions.evaluate(task.nodeType(), task.status(), task.scheduleStatus(),
                task.blockedFromStatus(), req.action(), predecessorsDone, req.reason(), req.remainingMinutes());

        applySideEffects(companyId, task, req, newStatus, access);
        events.insert(companyId, taskId, task.status(), newStatus, access.memberId(), req.reason());
        taskService.recomputeAncestors(companyId, task, access.memberId());
        return taskService.detail(companyId, taskId);
    }

    private void requireActionPermission(TaskRow task, TransitionRequest req,
                                         TaskAccessService.ProjectAccess access) {
        boolean manageOnly = switch (req.action()) {
            case MARK_READY, RETURN, ACCEPT, CANCEL, REOPEN -> true;
            case START, BLOCK, UNBLOCK, SUBMIT_REVIEW -> false;
        };
        if (manageOnly) {
            accessService.requireManager(access);
            return;
        }
        UUID assignee = task.effectiveAssignee();
        if (!access.canManage() && (assignee == null || !assignee.equals(access.memberId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只有任务负责人或项目管理人员可以执行该操作");
        }
    }

    private void applySideEffects(UUID companyId, TaskRow task, TransitionRequest req, TaskStatus newStatus,
                                  TaskAccessService.ProjectAccess access) {
        UUID taskId = task.id();
        switch (req.action()) {
            case MARK_READY -> {
                if (task.estimatedMinutes() == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "ESTIMATE_REQUIRED",
                            "请先确认预估工时再标记为可执行");
                }
                if (task.baselineMinutes() == null) {
                    tasks.setBaselineIfNull(companyId, taskId, task.estimatedMinutes());
                }
                tasks.updateState(companyId, taskId, newStatus, task.scheduleStatus(), null, null,
                        task.estimatedMinutes(), null, null, null);
            }
            case START -> {
                allocations.start(companyId, taskId, task.effectiveAssignee());
                tasks.updateState(companyId, taskId, newStatus, task.scheduleStatus(), null, null,
                        task.remainingMinutes(), null, null, null);
            }
            case BLOCK -> tasks.updateState(companyId, taskId, newStatus, task.scheduleStatus(), task.status(),
                    req.reason(), task.remainingMinutes(), null, null, null);
            case UNBLOCK -> tasks.updateState(companyId, taskId, newStatus, task.scheduleStatus(), null, null,
                    task.remainingMinutes(), null, null, null);
            case SUBMIT_REVIEW -> tasks.updateState(companyId, taskId, newStatus, task.scheduleStatus(), null, null,
                    task.remainingMinutes(), null, null, null);
            case RETURN -> {
                int remaining = TaskService.ceil30(req.remainingMinutes());
                tasks.updateState(companyId, taskId, newStatus, task.scheduleStatus(), null, null,
                        remaining, null, null, null);
                long version = remainingEstimates.nextVersion(companyId, taskId);
                remainingEstimates.insert(companyId, UUID.randomUUID(), taskId, version,
                        clock.today(companyId), remaining, access.memberId());
            }
            case ACCEPT -> {
                allocations.close(companyId, taskId);
                tasks.updateState(companyId, taskId, newStatus, ScheduleStatus.CLOSED, null, null,
                        0, Instant.now(), null, null);
            }
            case CANCEL -> {
                allocations.close(companyId, taskId);
                tasks.updateState(companyId, taskId, newStatus, ScheduleStatus.CLOSED, null, null,
                        task.remainingMinutes(), null, Instant.now(), req.reason());
                cascadeCancelChildren(companyId, task, req.reason(), access);
                blockUnfinishedSuccessors(companyId, task, access);
            }
            case REOPEN -> {
                int remaining = TaskService.ceil30(req.remainingMinutes());
                tasks.updateState(companyId, taskId, newStatus, ScheduleStatus.UNSCHEDULED, null, null,
                        remaining, null, null, null);
                long revision = revisions.nextRevision(companyId, taskId);
                var content = mapper.createObjectNode();
                content.put("reason", req.reason());
                content.put("remainingMinutes", remaining);
                revisions.insert(companyId, taskId, revision, content.toString(), RevisionSource.REOPEN,
                        access.memberId());
                long version = remainingEstimates.nextVersion(companyId, taskId);
                remainingEstimates.insert(companyId, UUID.randomUUID(), taskId, version,
                        clock.today(companyId), remaining, access.memberId());
            }
        }
    }

    /** 取消父任务时级联取消未终结的子任务。 */
    private void cascadeCancelChildren(UUID companyId, TaskRow parent, String reason,
                                       TaskAccessService.ProjectAccess access) {
        if (parent.nodeType() != NodeType.PARENT) return;
        for (TaskRow child : tasks.listSubtree(companyId, parent.id())) {
            if (child.status() == TaskStatus.DONE || child.status() == TaskStatus.CANCELED) continue;
            allocations.close(companyId, child.id());
            tasks.updateState(companyId, child.id(), TaskStatus.CANCELED, ScheduleStatus.CLOSED, null, null,
                    child.remainingMinutes(), null, Instant.now(), reason);
            events.insert(companyId, child.id(), child.status(), TaskStatus.CANCELED, access.memberId(),
                    "父任务取消级联：" + reason);
            blockUnfinishedSuccessors(companyId, child, access);
        }
    }

    /** 前置取消不等于完成：未完成的直接后继进入阻塞（TRD 5.2）。 */
    private void blockUnfinishedSuccessors(UUID companyId, TaskRow predecessor,
                                           TaskAccessService.ProjectAccess access) {
        for (TaskRow successor : dependencies.listSuccessors(companyId, predecessor.id())) {
            if (successor.status() != TaskStatus.READY && successor.status() != TaskStatus.IN_PROGRESS) continue;
            String reason = "前置任务被取消（任务编号 " + predecessor.taskNo() + "）";
            tasks.updateState(companyId, successor.id(), TaskStatus.BLOCKED, successor.scheduleStatus(),
                    successor.status(), reason, successor.remainingMinutes(), null, null, null);
            events.insert(companyId, successor.id(), successor.status(), TaskStatus.BLOCKED, access.memberId(), reason);
        }
    }
}
