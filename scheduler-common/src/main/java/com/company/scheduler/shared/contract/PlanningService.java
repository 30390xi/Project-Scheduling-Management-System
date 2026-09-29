package com.company.scheduler.shared.contract;

import java.util.List;
import java.util.UUID;

/** Implemented by planning. A proposal never occupies capacity; commit revalidates all constraints. */
public interface PlanningService {
    SimulationJob requestSimulation(UUID actorId, UUID companyId, List<UUID> rootTaskIds);
    CommitResult commit(UUID actorId, UUID companyId, UUID planId, long planVersion, String idempotencyKey);

    /** Query this job to obtain the generated planId; jobId and planId are distinct identifiers. */
    record SimulationJob(UUID jobId) {}
    record CommitResult(UUID planId, long revision) {}
}
