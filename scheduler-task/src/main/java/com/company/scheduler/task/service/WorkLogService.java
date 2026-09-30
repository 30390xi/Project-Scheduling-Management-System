package com.company.scheduler.task.service;

import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.api.dto.WorkLogRequest;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.store.TaskRepository;
import com.company.scheduler.task.store.TaskRow;
import com.company.scheduler.task.store.WorkLogRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 实际工时记录：事实流水，幂等提交，不重复扣容量。 */
@Service
public class WorkLogService {
    private final TaskRepository tasks;
    private final WorkLogRepository workLogs;
    private final TaskAccessService accessService;
    private final CompanyClock clock;

    public WorkLogService(TaskRepository tasks, WorkLogRepository workLogs,
                          TaskAccessService accessService, CompanyClock clock) {
        this.tasks = tasks;
        this.workLogs = workLogs;
        this.accessService = accessService;
        this.clock = clock;
    }

    @Transactional
    public TaskDetail.WorkLogView add(UUID userId, UUID companyId, UUID taskId, WorkLogRequest req,
                                      String idempotencyKey) {
        TaskRow task = tasks.lockById(companyId, taskId).orElseThrow(BusinessException::notFound);
        var access = accessService.requireProjectAccess(userId, companyId, task.projectId());
        UUID assignee = task.effectiveAssignee();
        if (!access.canManage() && (assignee == null || !assignee.equals(access.memberId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只能为本人负责的任务记录工时");
        }
        if (task.nodeType() != NodeType.LEAF) {
            throw new BusinessException(HttpStatus.CONFLICT, "PARENT_TASK_NOT_EXECUTABLE", "父任务不直接记录执行工时");
        }
        if (task.status() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "只有进行中的任务可以记录工时");
        }
        if (assignee == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "任务尚未分配负责人");
        }
        if (req.workDate().isAfter(clock.today(companyId))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "工时日期不能晚于公司今日");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = workLogs.findByIdempotencyKey(companyId, taskId, assignee, idempotencyKey);
            if (existing.isPresent()) return toView(existing.get());
        }
        // 任务行锁 + 唯一索引保证幂等：持锁期间不会出现同键并发插入
        var row = new WorkLogRepository.WorkLogRow(UUID.randomUUID(), taskId, assignee, req.workDate(),
                req.minutes(), req.note(), idempotencyKey, access.memberId(), null);
        workLogs.insert(companyId, row);
        tasks.addActualMinutes(companyId, taskId, req.minutes());
        return toView(row);
    }

    private TaskDetail.WorkLogView toView(WorkLogRepository.WorkLogRow row) {
        return new TaskDetail.WorkLogView(row.id(), row.memberId(), row.workDate(), row.minutes(), row.note(),
                row.createdBy(), row.createdAt());
    }
}
