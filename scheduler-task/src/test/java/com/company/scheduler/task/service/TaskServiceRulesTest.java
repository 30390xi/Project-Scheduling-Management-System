package com.company.scheduler.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.store.TaskRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskServiceRulesTest {

    @Test
    void ceil30RoundsUpToPlanningGranularity() {
        assertThat(TaskService.ceil30(30)).isEqualTo(30);
        assertThat(TaskService.ceil30(31)).isEqualTo(60);
        assertThat(TaskService.ceil30(305)).isEqualTo(330);
        assertThatThrownBy(() -> TaskService.ceil30(0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TaskService.ceil30(-5)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aggregateStatusFollowsChildren() {
        assertThat(TaskService.aggregateStatus(List.of())).isEqualTo(TaskStatus.DRAFT);
        assertThat(TaskService.aggregateStatus(List.of(child(TaskStatus.DONE), child(TaskStatus.DONE))))
                .isEqualTo(TaskStatus.DONE);
        assertThat(TaskService.aggregateStatus(List.of(child(TaskStatus.DONE), child(TaskStatus.IN_REVIEW))))
                .isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(TaskService.aggregateStatus(List.of(child(TaskStatus.READY), child(TaskStatus.BLOCKED))))
                .isEqualTo(TaskStatus.BLOCKED);
        assertThat(TaskService.aggregateStatus(List.of(child(TaskStatus.DONE), child(TaskStatus.READY))))
                .isEqualTo(TaskStatus.READY);
        assertThat(TaskService.aggregateStatus(List.of(child(TaskStatus.DRAFT), child(TaskStatus.SUBMITTED))))
                .isEqualTo(TaskStatus.SUBMITTED);
        assertThat(TaskService.aggregateStatus(List.of(child(TaskStatus.DRAFT), child(TaskStatus.DRAFT))))
                .isEqualTo(TaskStatus.DRAFT);
    }

    private TaskRow child(TaskStatus status) {
        return new TaskRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, NodeType.LEAF, null,
                status, ScheduleStatus.UNSCHEDULED, null, null, null, "t", null, null,
                com.company.scheduler.task.domain.TaskPriority.P2,
                com.company.scheduler.task.domain.DueType.SOFT, null, null, null, null, null,
                null, null, null, 0, null, null, null, null, null, UUID.randomUUID(), null, null, 0);
    }
}
