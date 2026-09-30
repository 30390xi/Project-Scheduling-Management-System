package com.company.scheduler.task.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record WorkLogRequest(
        @NotNull LocalDate workDate,
        @NotNull @Positive Integer minutes,
        @Size(max = 1000) String note) {}
