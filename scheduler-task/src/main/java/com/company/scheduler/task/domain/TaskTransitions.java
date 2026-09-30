package com.company.scheduler.task.domain;

import com.company.scheduler.shared.error.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 任务业务状态机（纯函数，不接触数据库）。
 * 状态路径：DRAFT → SUBMITTED → NEEDS_INFO / READY → IN_PROGRESS → IN_REVIEW → DONE；
 * READY/IN_PROGRESS 可 BLOCKED 并回到原阶段；非终态可 CANCELED；DONE 可受控 REOPEN 回 READY。
 * 父任务是汇总节点：只接受 CANCEL，其余状态由子任务聚合推导。
 */
public final class TaskTransitions {
    private TaskTransitions() {}

    public static TaskStatus evaluate(NodeType nodeType, TaskStatus status, ScheduleStatus scheduleStatus,
                                      TaskStatus blockedFrom, TransitionAction action,
                                      boolean predecessorsDone, String reason, Integer remainingMinutes) {
        if (nodeType == NodeType.PARENT && action != TransitionAction.CANCEL) {
            throw new BusinessException(HttpStatus.CONFLICT, "PARENT_TASK_NOT_EXECUTABLE", "父任务是汇总节点，状态由子任务聚合，不能直接操作");
        }
        switch (action) {
            case MARK_READY -> {
                requireStatus(status, TaskStatus.SUBMITTED, TaskStatus.NEEDS_INFO);
                return TaskStatus.READY;
            }
            case START -> {
                requireStatus(status, TaskStatus.READY);
                if (scheduleStatus != ScheduleStatus.PLANNED) {
                    throw new BusinessException(HttpStatus.CONFLICT, "SCHEDULE_NOT_PLANNED", "任务没有有效排期，不能开工");
                }
                if (!predecessorsDone) {
                    throw new BusinessException(HttpStatus.CONFLICT, "PREDECESSOR_NOT_DONE", "前置任务尚未验收完成，不能开工");
                }
                return TaskStatus.IN_PROGRESS;
            }
            case BLOCK -> {
                requireStatus(status, TaskStatus.READY, TaskStatus.IN_PROGRESS);
                requireReason(reason);
                return TaskStatus.BLOCKED;
            }
            case UNBLOCK -> {
                requireStatus(status, TaskStatus.BLOCKED);
                if (blockedFrom == null) {
                    throw invalid("该任务缺少阻塞前状态，不能解除阻塞");
                }
                return blockedFrom;
            }
            case SUBMIT_REVIEW -> {
                requireStatus(status, TaskStatus.IN_PROGRESS);
                return TaskStatus.IN_REVIEW;
            }
            case RETURN -> {
                requireStatus(status, TaskStatus.IN_REVIEW);
                if (remainingMinutes == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REMAINING_REQUIRED", "退回进行中必须确认剩余工时");
                }
                return TaskStatus.IN_PROGRESS;
            }
            case ACCEPT -> {
                requireStatus(status, TaskStatus.IN_REVIEW);
                return TaskStatus.DONE;
            }
            case CANCEL -> {
                if (status == TaskStatus.DONE || status == TaskStatus.CANCELED) {
                    throw invalid("终态任务不能取消");
                }
                requireReason(reason);
                return TaskStatus.CANCELED;
            }
            case REOPEN -> {
                requireStatus(status, TaskStatus.DONE);
                requireReason(reason);
                if (remainingMinutes == null || remainingMinutes <= 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REMAINING_REQUIRED", "重开必须提供大于 0 的新剩余工时");
                }
                return TaskStatus.READY;
            }
            default -> throw invalid("不支持的状态操作");
        }
    }

    private static void requireStatus(TaskStatus status, TaskStatus... allowed) {
        for (TaskStatus candidate : allowed) {
            if (status == candidate) return;
        }
        throw invalid("当前状态 " + status + " 不允许该操作");
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "该操作必须填写原因");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", message);
    }
}
