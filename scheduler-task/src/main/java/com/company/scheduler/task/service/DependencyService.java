package com.company.scheduler.task.service;

import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.DependencyUpdateRequest;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.domain.DependencyGraph;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.store.TaskDependencyRepository;
import com.company.scheduler.task.store.TaskRepository;
import com.company.scheduler.task.store.TaskRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同项目叶子任务 FS 依赖。保存前在 guard 写锁内完成无环校验（AC-08）；
 * 禁止自依赖、跨项目、指向已取消任务的依赖。
 */
@Service
public class DependencyService {
    private final TaskRepository tasks;
    private final TaskDependencyRepository dependencies;
    private final TaskAccessService accessService;
    private final PlanningGuardService guard;
    private final TaskService taskService;

    public DependencyService(TaskRepository tasks, TaskDependencyRepository dependencies,
                             TaskAccessService accessService, PlanningGuardService guard,
                             TaskService taskService) {
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.accessService = accessService;
        this.guard = guard;
        this.taskService = taskService;
    }

    @Transactional
    public TaskDetail replacePredecessors(UUID userId, UUID companyId, UUID taskId,
                                          DependencyUpdateRequest req, long expectedVersion) {
        // 先取 guard 写锁，串行化公司内依赖变更，保证环检测与写入之间无并发插入（TRD 9.2）
        guard.lockAndBump(companyId);
        TaskRow task = taskService.lockAndCheckVersion(companyId, taskId, expectedVersion);
        var access = accessService.requireProjectAccess(userId, companyId, task.projectId());
        accessService.requireManager(access);
        if (task.nodeType() != NodeType.LEAF) {
            throw new BusinessException(HttpStatus.CONFLICT, "PARENT_TASK_NOT_EXECUTABLE", "依赖只能建立在叶子任务之间");
        }
        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELED) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "终态任务不能修改依赖");
        }

        var predecessorIds = new ArrayList<>(new LinkedHashSet<>(req.predecessorIds()));
        if (predecessorIds.size() != req.predecessorIds().size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "前置任务列表存在重复");
        }
        if (predecessorIds.contains(taskId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "DEPENDENCY_CYCLE", "不允许任务依赖自身");
        }
        for (UUID predecessorId : predecessorIds) {
            TaskRow predecessor = tasks.find(companyId, predecessorId).orElseThrow(BusinessException::notFound);
            if (!predecessor.projectId().equals(task.projectId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "首发仅支持同项目内依赖");
            }
            if (predecessor.nodeType() != NodeType.LEAF) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "依赖只能建立在叶子任务之间");
            }
            if (predecessor.status() == TaskStatus.CANCELED) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                        "不能依赖已取消的任务，请替换依赖或调整需求");
            }
        }

        // 用替换后的边集做全项目环检测
        List<DependencyGraph.Edge> edges = new ArrayList<>(dependencies.listEdgesByProject(companyId, task.projectId())
                .stream().filter(e -> !e.successor().equals(taskId)).toList());
        for (UUID predecessorId : predecessorIds) {
            edges.add(new DependencyGraph.Edge(predecessorId, taskId));
        }
        var cycle = DependencyGraph.findCycle(edges);
        if (cycle.isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "DEPENDENCY_CYCLE",
                    "依赖形成环路：" + cycle.get());
        }

        dependencies.replacePredecessors(companyId, task.projectId(), taskId, predecessorIds);
        tasks.touch(companyId, taskId);
        return taskService.detail(companyId, taskId);
    }
}
