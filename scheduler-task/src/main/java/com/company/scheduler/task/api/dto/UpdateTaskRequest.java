package com.company.scheduler.task.api.dto;

import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 需求字段整体替换语义；skills 为 null 表示保持不变，空列表表示清空。 */
public record UpdateTaskRequest(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 20000) String description,
        @Size(max = 64) String taskType,
        TaskPriority priority,
        LocalDate dueDate,
        DueType dueType,
        LocalDate earliestStartDate,
        @Positive Integer estimatedMinutes,
        UUID fixedAssigneeId,
        UUID preferredMemberId,
        @Size(max = 5000) String acceptanceCriteria,
        List<SkillRequirementDto> skills) {}
