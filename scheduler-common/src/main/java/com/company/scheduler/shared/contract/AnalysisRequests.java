package com.company.scheduler.shared.contract;
import java.util.UUID;
public interface AnalysisRequests {
    Submission submit(Request request);
    record Request(UUID userId, UUID companyId, UUID projectId, UUID memberId, UUID taskId,
                   long taskVersion, String taskText) {}
    record Submission(UUID runId, UUID jobId) {}
}
