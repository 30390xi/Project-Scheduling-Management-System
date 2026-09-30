package com.company.scheduler.task.store;

import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskPriority;
import com.company.scheduler.task.domain.TaskStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** task 表当前业务快照行。 */
public record TaskRow(
        UUID id, UUID companyId, UUID projectId, UUID parentId, NodeType nodeType, Long taskNo,
        TaskStatus status, ScheduleStatus scheduleStatus, TaskStatus blockedFromStatus, String blockedReason,
        String cancelReason,
        String title, String description, String taskType, TaskPriority priority, DueType dueType,
        LocalDate dueDate, LocalDate earliestStartDate,
        UUID assigneeId, UUID fixedAssigneeId, UUID preferredMemberId,
        Integer estimatedMinutes, Integer baselineMinutes, Integer remainingMinutes, int actualMinutes,
        String estimateSource, String acceptanceCriteria,
        Instant submittedAt, Instant completedAt, Instant canceledAt,
        UUID createdBy, Instant createdAt, Instant updatedAt, long version) {

    /** 当前有效负责人：正式负责人优先，其次硬指定成员。 */
    public UUID effectiveAssignee() {
        return assigneeId != null ? assigneeId : fixedAssigneeId;
    }
}
