package com.company.scheduler.agent.job;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次已领取的作业快照。执行结果必须以 (id, leaseOwner, leaseGeneration) 条件写入，
 * 防止租约过期被回收后，旧工作者覆盖新工作者的结果。
 */
public record ClaimedJob(
        UUID id,
        UUID companyId,
        String jobType,
        UUID aggregateId,
        long aggregateVersion,
        String idempotencyKey,
        int attempts,
        int maxAttempts,
        String leaseOwner,
        long leaseGeneration,
        Instant leaseUntil,
        String payloadJson) {

    public ClaimedJob {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("claimed job requires a lease owner");
        }
    }
}
