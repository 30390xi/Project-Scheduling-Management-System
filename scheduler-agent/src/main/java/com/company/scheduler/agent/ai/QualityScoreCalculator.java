package com.company.scheduler.agent.ai;

import org.springframework.stereotype.Service;

/**
 * 质量分（TRD 7.5）：Q = 0.35*完整性 + 0.25*案例证据 + 0.20*区间稳定性 + 0.20*模型自报。
 * 这是工程启发式分数，不是概率；独立阻断条件（缺验收标准、未确认关键假设、输出非法）
 * 在 ConfirmationPolicy 中单独判定，不能被总分抵消。
 */
@Service
public class QualityScoreCalculator {

    public record Components(double completeness, double caseEvidence, double intervalStability,
            double modelSelfReport, boolean coldStart) {
        public double weighted() {
            return 0.35 * clamp01(completeness) + 0.25 * clamp01(caseEvidence)
                    + 0.20 * clamp01(intervalStability) + 0.20 * clamp01(modelSelfReport);
        }
    }

    /** @param historicalExamplesAvailable 历史相似案例是否可用；冷启动证据项取 0.5 并标注 */
    public Components evaluate(AiAnalysisOutput output, boolean historicalExamplesAvailable) {
        double completeness = evaluateCompleteness(output);
        double caseEvidence = historicalExamplesAvailable ? 1.0 : 0.5;
        double intervalStability = evaluateIntervalStability(output.estimate());
        double selfReport = output.confidence() == null ? 0.0 : output.confidence();
        return new Components(completeness, caseEvidence, intervalStability,
                Math.min(Math.max(selfReport, 0), 1), !historicalExamplesAvailable);
    }

    /** 关键字段完整性：摘要、验收标准、估算、技能、假设说明、拆解任务的交付物。 */
    private double evaluateCompleteness(AiAnalysisOutput output) {
        int present = 0;
        int total = 6;
        if (output.summary() != null && !output.summary().isBlank()) {
            present++;
        }
        if (!output.acceptanceCriteria().isEmpty()) {
            present++;
        }
        if (output.estimate() != null && output.estimate().basis() != null
                && !output.estimate().basis().isBlank()) {
            present++;
        }
        if (!output.requiredSkills().isEmpty()) {
            present++;
        }
        if (output.assumptions() != null) {
            present++;
        }
        boolean subtasksComplete = output.subtasks().isEmpty() || output.subtasks().stream()
                .allMatch(subtask -> !subtask.deliverables().isEmpty()
                        && !subtask.acceptanceCriteria().isEmpty()
                        && subtask.estimate() != null);
        if (subtasksComplete) {
            present++;
        }
        return (double) present / total;
    }

    /** 区间稳定性：悲观/乐观比越接近 1 越稳定；比值 1→1.0，≥2.5→0（线性）。 */
    double evaluateIntervalStability(AiAnalysisOutput.Estimate estimate) {
        if (estimate == null || estimate.optimisticMinutes() == null
                || estimate.optimisticMinutes() <= 0 || estimate.pessimisticMinutes() == null) {
            return 0.0;
        }
        double ratio = (double) estimate.pessimisticMinutes() / estimate.optimisticMinutes();
        if (ratio <= 1.0) {
            return 1.0;
        }
        return clamp01(1 - (ratio - 1) / 1.5);
    }

    private static double clamp01(double value) {
        return Math.min(Math.max(value, 0.0), 1.0);
    }
}
