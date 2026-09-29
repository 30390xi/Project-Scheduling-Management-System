package com.company.scheduler.shared.contract;

import java.util.UUID;

/** Future reliable dispatcher: persist jobs in the caller's business transaction. */
public interface JobDispatcher {
    UUID enqueue(UUID companyId, String jobType, UUID aggregateId, long aggregateVersion, String idempotencyKey);
}
