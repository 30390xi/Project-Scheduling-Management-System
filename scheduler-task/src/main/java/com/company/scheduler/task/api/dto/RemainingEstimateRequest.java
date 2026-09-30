package com.company.scheduler.task.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

public record RemainingEstimateRequest(
        @NotNull @PositiveOrZero Integer remainingMinutes,
        @NotNull LocalDate effectiveAfterDate) {}
