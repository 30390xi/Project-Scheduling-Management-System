package com.company.scheduler.agent.ai;

import java.time.Instant;
import java.util.UUID;

/** ai_analysis_run 领域视图。status: PENDING → RUNNING → SUCCEEDED / FAILED / REUSED。 */
public record AiAnalysisRun(
        UUID id,
        UUID companyId,
        UUID jobId,
        UUID submittedByMemberId,
        UUID taskAggregateId,
        String taskTextHash,
        String inputSnapshotJson,
        String model,
        String promptVersion,
        String schemaVersion,
        String status,
        int providerCalls,
        String rawOutputJson,
        String validatedOutputJson,
        String usageJson,
        Double qualityScore,
        String errorCode,
        String errorDetail,
        Instant createdAt,
        Instant updatedAt) {

    public boolean terminal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "REUSED".equals(status);
    }
}
