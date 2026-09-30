package com.company.scheduler.task.api.dto;

import com.company.scheduler.task.domain.TransitionAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TransitionRequest(
        @NotNull TransitionAction action,
        @Size(max = 1000) String reason,
        @Positive Integer remainingMinutes,
        @Size(max = 1000) String note) {}
