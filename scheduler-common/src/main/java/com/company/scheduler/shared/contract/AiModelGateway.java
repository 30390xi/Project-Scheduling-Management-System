package com.company.scheduler.shared.contract;

import java.util.List;
import java.util.UUID;

/** No database or scheduling write authority belongs to a model adapter. */
public interface AiModelGateway {
    AnalysisResult analyze(AnalysisRequest request);

    record AnalysisRequest(UUID runId, UUID companyId, String taskText, List<String> allowedSkillCodes) {
        public AnalysisRequest { allowedSkillCodes = List.copyOf(allowedSkillCodes); }
    }
    record AnalysisResult(String schemaVersion, String structuredJson, String modelVersion) {}
}
