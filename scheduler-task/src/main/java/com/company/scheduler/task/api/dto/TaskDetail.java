package com.company.scheduler.task.api.dto;

import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.RevisionSource;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskPriority;
import com.company.scheduler.task.domain.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskDetail(
        UUID id, Long taskNo, UUID projectId, UUID parentId, NodeType nodeType, String title,
        String description, String taskType, TaskPriority priority, TaskStatus status,
        ScheduleStatus scheduleStatus, TaskStatus blockedFromStatus, String blockedReason,
        String cancelReason, UUID assigneeId, UUID fixedAssigneeId, UUID preferredMemberId,
        Integer estimatedMinutes, Integer baselineMinutes, Integer remainingMinutes, int actualMinutes,
        String estimateSource, String acceptanceCriteria,
        LocalDate dueDate, DueType dueType, LocalDate earliestStartDate,
        Instant submittedAt, Instant completedAt, Instant canceledAt,
        UUID createdBy, Instant createdAt, Instant updatedAt, long version,
        List<SkillRequirementView> skills,
        List<TaskSummary> children,
        List<DependencyView> predecessors,
        List<DependencyView> successors,
        ProgressView progress,
        List<RevisionView> revisions,
        List<StatusEventView> events,
        List<WorkLogView> workLogs,
        List<RemainingEstimateView> remainingEstimates) {

    public record SkillRequirementView(String skillCode, int minLevel, boolean required) {}

    public record DependencyView(UUID taskId, Long taskNo, String title, TaskStatus status) {}

    /** 父任务聚合进度：按叶子计划工时加权，不采用已记录工时伪造进度。 */
    public record ProgressView(int leafCount, int doneLeafCount, int plannedMinutes,
                               int actualMinutes, int remainingMinutes) {}

    public record RevisionView(long revision, RevisionSource source, UUID actorId,
                               Instant createdAt, JsonNode content) {}

    public record StatusEventView(TaskStatus fromStatus, TaskStatus toStatus, UUID actorId,
                                  String reason, Instant createdAt) {}

    public record WorkLogView(UUID id, UUID memberId, LocalDate workDate, int minutes,
                              String note, UUID createdBy, Instant createdAt) {}

    public record RemainingEstimateView(long version, LocalDate effectiveAfterDate, int remainingMinutes,
                                        UUID confirmedBy, Instant createdAt) {}
}
