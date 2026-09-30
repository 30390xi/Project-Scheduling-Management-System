package com.company.scheduler.task.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 整体替换某任务的前置任务集合（同项目叶子 FS 依赖）。 */
public record DependencyUpdateRequest(
        @NotNull @Size(max = 30) List<UUID> predecessorIds) {}
