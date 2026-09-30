package com.company.scheduler.task.api.dto;

import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskPriority;
import com.company.scheduler.task.domain.TaskStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskSummary(
        UUID id, Long taskNo, UUID projectId, UUID parentId, NodeType nodeType, String title,
        String taskType, TaskPriority priority, TaskStatus status, ScheduleStatus scheduleStatus,
        UUID assigneeId, Integer estimatedMinutes, int actualMinutes, Integer remainingMinutes,
        LocalDate dueDate, DueType dueType, Instant updatedAt, long version) {}
