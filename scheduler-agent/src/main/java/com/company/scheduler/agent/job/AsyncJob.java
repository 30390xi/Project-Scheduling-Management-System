package com.company.scheduler.agent.job;

import java.time.Instant;
import java.util.UUID;

/** async_job 表的领域视图。 */
public record AsyncJob(
        UUID id,
        UUID companyId,
        String jobType,
        UUID aggregateId,
        long aggregateVersion,
        String idempotencyKey,
        AsyncJobStatus status,
        int attempts,
        int maxAttempts,
        String leaseOwner,
        Instant leaseUntil,
        long leaseGeneration,
        Instant nextRunAt,
        String payloadJson,
        String resultJson,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {
}
