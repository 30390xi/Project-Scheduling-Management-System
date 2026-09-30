package com.company.scheduler.task.service;

import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.RemainingEstimateRequest;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.store.TaskRemainingEstimateRepository;
import com.company.scheduler.task.store.TaskRepository;
import com.company.scheduler.task.store.TaskRow;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 剩余工时确认：版本化记录并递增 guard revision，未来重排由 C 依据确认值执行。 */
@Service
public class RemainingEstimateService {
    private final TaskRepository tasks;
    private final TaskRemainingEstimateRepository remainingEstimates;
    private final TaskAccessService accessService;
    private final PlanningGuardService guard;
    private final CompanyClock clock;
    private final TaskService taskService;

    public RemainingEstimateService(TaskRepository tasks, TaskRemainingEstimateRepository remainingEstimates,
                                    TaskAccessService accessService, PlanningGuardService guard,
                                    CompanyClock clock, TaskService taskService) {
        this.tasks = tasks;
        this.remainingEstimates = remainingEstimates;
        this.accessService = accessService;
        this.guard = guard;
        this.clock = clock;
        this.taskService = taskService;
    }

    @Transactional
    public TaskDetail confirm(UUID userId, UUID companyId, UUID taskId, RemainingEstimateRequest req,
                              long expectedVersion) {
        guard.lockAndBump(companyId);
        TaskRow task = taskService.lockAndCheckVersion(companyId, taskId, expectedVersion);
        var access = accessService.requireProjectAccess(userId, companyId, task.projectId());
        UUID assignee = task.effectiveAssignee();
        if (!access.canManage() && (assignee == null || !assignee.equals(access.memberId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只有任务负责人或项目管理人员可以确认剩余工时");
        }
        if (task.nodeType() != NodeType.LEAF) {
            throw new BusinessException(HttpStatus.CONFLICT, "PARENT_TASK_NOT_EXECUTABLE", "父任务工时由子任务聚合");
        }
        if (task.status() != TaskStatus.IN_PROGRESS && task.status() != TaskStatus.BLOCKED) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                    "只有进行中或阻塞中的任务可以确认剩余工时");
        }
        if (req.effectiveAfterDate().isBefore(clock.today(companyId))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "生效日期不能早于公司今日");
        }
        int remaining = req.remainingMinutes() == 0 ? 0 : TaskService.ceil30(req.remainingMinutes());
        long version = remainingEstimates.nextVersion(companyId, taskId);
        remainingEstimates.insert(companyId, UUID.randomUUID(), taskId, version, req.effectiveAfterDate(),
                remaining, access.memberId());
        tasks.updateRemainingMinutes(companyId, taskId, remaining);
        return taskService.detail(companyId, taskId);
    }
}
