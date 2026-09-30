package com.company.scheduler.task.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SkillRequirementDto(
        @NotBlank @Size(max = 64) String skillCode,
        @Min(1) @Max(5) int minLevel,
        boolean required) {}
