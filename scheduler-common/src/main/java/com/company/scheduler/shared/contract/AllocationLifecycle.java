package com.company.scheduler.shared.contract;
import java.util.UUID;
/** Internal transactional port: caller must authorize task and lock company guard first. */
public interface AllocationLifecycle {
    void start(UUID companyId, UUID taskId, UUID memberId);
    void close(UUID companyId, UUID taskId);
}
