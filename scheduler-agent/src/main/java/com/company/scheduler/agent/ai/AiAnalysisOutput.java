package com.company.scheduler.agent.ai;

import java.util.List;

/**
 * 模型结构化输出契约（TRD 7.3），按严格模式反序列化：未知字段直接失败。
 * 所有估算字段是整数分钟；confirmed 只能由人工置 true，模型输出 true 视为伪造。
 */
public record AiAnalysisOutput(
        String schemaVersion,
        String taskType,
        String summary,
        Boolean requiresDecomposition,
        Estimate estimate,
        List<SkillRequirement> requiredSkills,
        List<String> acceptanceCriteria,
        List<Assumption> assumptions,
        List<String> missingInformation,
        Double confidence,
        List<Subtask> subtasks,
        List<DependencyEdge> dependencies) {

    public AiAnalysisOutput {
        requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
        subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public record Estimate(Integer optimisticMinutes, Integer likelyMinutes, Integer pessimisticMinutes,
            String basis) {}

    public record SkillRequirement(String code, Integer minLevel) {}

    public record Assumption(String text, Boolean confirmed) {}

    public record Subtask(String clientKey, String title, String scope, List<String> deliverables,
            List<String> acceptanceCriteria, Estimate estimate, List<SkillRequirement> requiredSkills) {
        public Subtask {
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
            requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
        }
    }

    public record DependencyEdge(String from, String to, String type) {}
}
