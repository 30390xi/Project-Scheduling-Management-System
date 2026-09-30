package com.company.scheduler.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.scheduler.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

class TaskTransitionsTest {

    @Test
    void happyPathDraftToDone() {
        // SUBMITTED → READY → IN_PROGRESS → IN_REVIEW → DONE
        assertThat(evaluate(NodeType.LEAF, TaskStatus.SUBMITTED, ScheduleStatus.UNSCHEDULED, null,
                TransitionAction.MARK_READY, true, null, null)).isEqualTo(TaskStatus.READY);
        assertThat(evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.PLANNED, null,
                TransitionAction.START, true, null, null)).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evaluate(NodeType.LEAF, TaskStatus.IN_PROGRESS, ScheduleStatus.PLANNED, null,
                TransitionAction.SUBMIT_REVIEW, true, null, null)).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(evaluate(NodeType.LEAF, TaskStatus.IN_REVIEW, ScheduleStatus.PLANNED, null,
                TransitionAction.ACCEPT, true, null, null)).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void startRequiresPlannedSchedule() {
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.UNSCHEDULED, null,
                TransitionAction.START, true, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("SCHEDULE_NOT_PLANNED");
        // STALE 计划同样禁止新开工
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.STALE, null,
                TransitionAction.START, true, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("SCHEDULE_NOT_PLANNED");
    }

    /** AC-10：前置未验收，到了计划日期也不能开工。 */
    @Test
    void startRequiresAllPredecessorsDone() {
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.PLANNED, null,
                TransitionAction.START, false, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("PREDECESSOR_NOT_DONE");
    }

    @Test
    void blockAndUnblockReturnsToOriginStatus() {
        assertThat(evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.UNSCHEDULED, null,
                TransitionAction.BLOCK, true, "等待外部接口", null)).isEqualTo(TaskStatus.BLOCKED);
        assertThat(evaluate(NodeType.LEAF, TaskStatus.IN_PROGRESS, ScheduleStatus.PLANNED, null,
                TransitionAction.BLOCK, true, "依赖失效", null)).isEqualTo(TaskStatus.BLOCKED);
        assertThat(evaluate(NodeType.LEAF, TaskStatus.BLOCKED, ScheduleStatus.PLANNED, TaskStatus.IN_PROGRESS,
                TransitionAction.UNBLOCK, true, null, null)).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(evaluate(NodeType.LEAF, TaskStatus.BLOCKED, ScheduleStatus.UNSCHEDULED, TaskStatus.READY,
                TransitionAction.UNBLOCK, true, null, null)).isEqualTo(TaskStatus.READY);
    }

    @Test
    void blockRequiresReason() {
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.UNSCHEDULED, null,
                TransitionAction.BLOCK, true, " ", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("REASON_REQUIRED");
    }

    @Test
    void returnToProgressRequiresRemainingEstimate() {
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.IN_REVIEW, ScheduleStatus.PLANNED, null,
                TransitionAction.RETURN, true, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("REMAINING_REQUIRED");
        assertThat(evaluate(NodeType.LEAF, TaskStatus.IN_REVIEW, ScheduleStatus.PLANNED, null,
                TransitionAction.RETURN, true, null, 120)).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void cancelRequiresReasonAndNonTerminalStatus() {
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.PLANNED, null,
                TransitionAction.CANCEL, true, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("REASON_REQUIRED");
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.CANCELED, ScheduleStatus.CLOSED, null,
                TransitionAction.CANCEL, true, "重复取消", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThat(evaluate(NodeType.LEAF, TaskStatus.READY, ScheduleStatus.PLANNED, null,
                TransitionAction.CANCEL, true, "需求取消", null)).isEqualTo(TaskStatus.CANCELED);
    }

    @Test
    void reopenRequiresReasonAndPositiveRemaining() {
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.DONE, ScheduleStatus.CLOSED, null,
                TransitionAction.REOPEN, true, "验收遗漏", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("REMAINING_REQUIRED");
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.DONE, ScheduleStatus.CLOSED, null,
                TransitionAction.REOPEN, true, null, 60))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("REASON_REQUIRED");
        assertThat(evaluate(NodeType.LEAF, TaskStatus.DONE, ScheduleStatus.CLOSED, null,
                TransitionAction.REOPEN, true, "验收遗漏", 60)).isEqualTo(TaskStatus.READY);
    }

    @Test
    void parentNodeIsAggregateOnly() {
        // 父任务不允许开始/工时/验收等叶子操作，只能取消
        for (TransitionAction action : TransitionAction.values()) {
            if (action == TransitionAction.CANCEL) continue;
            assertThatThrownBy(() -> evaluate(NodeType.PARENT, TaskStatus.READY, ScheduleStatus.UNSCHEDULED,
                    null, action, true, "原因", 60))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).code()).isEqualTo("PARENT_TASK_NOT_EXECUTABLE");
        }
        assertThat(evaluate(NodeType.PARENT, TaskStatus.READY, ScheduleStatus.UNSCHEDULED, null,
                TransitionAction.CANCEL, true, "整树取消", null)).isEqualTo(TaskStatus.CANCELED);
    }

    @Test
    void illegalTransitionsRejected() {
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.DRAFT, ScheduleStatus.UNSCHEDULED, null,
                TransitionAction.START, true, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThatThrownBy(() -> evaluate(NodeType.LEAF, TaskStatus.DONE, ScheduleStatus.CLOSED, null,
                TransitionAction.START, true, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    private TaskStatus evaluate(NodeType nodeType, TaskStatus status, ScheduleStatus scheduleStatus,
                                TaskStatus blockedFrom, TransitionAction action, boolean predecessorsDone,
                                String reason, Integer remainingMinutes) {
        return TaskTransitions.evaluate(nodeType, status, scheduleStatus, blockedFrom, action, predecessorsDone,
                reason, remainingMinutes);
    }
}
