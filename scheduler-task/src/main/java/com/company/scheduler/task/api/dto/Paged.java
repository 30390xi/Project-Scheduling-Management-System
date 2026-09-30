package com.company.scheduler.task.api.dto;

import java.util.List;

public record Paged<T>(List<T> items, String nextCursor) {}
