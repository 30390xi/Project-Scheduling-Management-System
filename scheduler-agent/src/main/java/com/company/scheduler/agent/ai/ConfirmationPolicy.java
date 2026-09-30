package com.company.scheduler.agent.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 自动派单准入策略（PRD 7.1）。确定性代码，模型无权参与判定。
 * 任何缺失证据都落到人工确认；不满足准入只影响自动提交，仍可生成方案供负责人审阅。
 */
@Service
public class ConfirmationPolicy {

    public record AdmissionInput(
            boolean projectAutoAssignEnabled,
            boolean firstDecomposition,
            boolean outputLegal,
            boolean infoComplete,
            boolean acceptanceCriteriaPresent,
            boolean allKeyAssumptionsConfirmed,
            boolean skillsMapped,
            boolean hardConstraintsPass,
            double qualityScore,
            double pessimisticOptimisticRatio) {

        /** 默认全关卡通过，测试/调用方按需关闭单项。 */
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean projectAutoAssignEnabled = true;
            private boolean firstDecomposition = false;
            private boolean outputLegal = true;
            private boolean infoComplete = true;
            private boolean acceptanceCriteriaPresent = true;
            private boolean allKeyAssumptionsConfirmed = true;
            private boolean skillsMapped = true;
            private boolean hardConstraintsPass = true;
            private double qualityScore = 0.9;
            private double pessimisticOptimisticRatio = 1.5;

            public Builder projectAutoAssignEnabled(boolean value) { this.projectAutoAssignEnabled = value; return this; }
            public Builder firstDecomposition(boolean value) { this.firstDecomposition = value; return this; }
            public Builder outputLegal(boolean value) { this.outputLegal = value; return this; }
            public Builder infoComplete(boolean value) { this.infoComplete = value; return this; }
            public Builder acceptanceCriteriaPresent(boolean value) { this.acceptanceCriteriaPresent = value; return this; }
            public Builder allKeyAssumptionsConfirmed(boolean value) { this.allKeyAssumptionsConfirmed = value; return this; }
            public Builder skillsMapped(boolean value) { this.skillsMapped = value; return this; }
            public Builder hardConstraintsPass(boolean value) { this.hardConstraintsPass = value; return this; }
            public Builder qualityScore(double value) { this.qualityScore = value; return this; }
            public Builder pessimisticOptimisticRatio(double value) { this.pessimisticOptimisticRatio = value; return this; }

            public AdmissionInput build() {
                return new AdmissionInput(projectAutoAssignEnabled, firstDecomposition, outputLegal,
                        infoComplete, acceptanceCriteriaPresent, allKeyAssumptionsConfirmed,
                        skillsMapped, hardConstraintsPass, qualityScore, pessimisticOptimisticRatio);
            }
        }
    }

    public record AdmissionDecision(boolean autoSubmitAllowed, List<String> blockingReasons) {}

    public static final String AUTO_ASSIGN_DISABLED = "AUTO_ASSIGN_DISABLED";
    public static final String FIRST_DECOMPOSITION_REQUIRES_HUMAN = "FIRST_DECOMPOSITION_REQUIRES_HUMAN";
    public static final String AI_OUTPUT_INVALID = "AI_OUTPUT_INVALID";
    public static final String INFO_INCOMPLETE = "INFO_INCOMPLETE";
    public static final String ACCEPTANCE_CRITERIA_MISSING = "ACCEPTANCE_CRITERIA_MISSING";
    public static final String UNCONFIRMED_KEY_ASSUMPTIONS = "UNCONFIRMED_KEY_ASSUMPTIONS";
    public static final String SKILL_NOT_MAPPED = "SKILL_NOT_MAPPED";
    public static final String HARD_CONSTRAINT_FAILED = "HARD_CONSTRAINT_FAILED";
    public static final String QUALITY_BELOW_THRESHOLD = "QUALITY_BELOW_THRESHOLD";
    public static final String ESTIMATE_INTERVAL_TOO_WIDE = "ESTIMATE_INTERVAL_TOO_WIDE";

    private final AiGatewayProperties properties;

    public ConfirmationPolicy(AiGatewayProperties properties) {
        this.properties = properties;
    }

    public AdmissionDecision decide(AdmissionInput input) {
        List<String> blocking = new ArrayList<>();
        if (!input.projectAutoAssignEnabled()) {
            blocking.add(AUTO_ASSIGN_DISABLED);
        }
        if (input.firstDecomposition()) {
            blocking.add(FIRST_DECOMPOSITION_REQUIRES_HUMAN);
        }
        if (!input.outputLegal()) {
            blocking.add(AI_OUTPUT_INVALID);
        }
        if (!input.infoComplete()) {
            blocking.add(INFO_INCOMPLETE);
        }
        if (!input.acceptanceCriteriaPresent()) {
            blocking.add(ACCEPTANCE_CRITERIA_MISSING);
        }
        if (!input.allKeyAssumptionsConfirmed()) {
            blocking.add(UNCONFIRMED_KEY_ASSUMPTIONS);
        }
        if (!input.skillsMapped()) {
            blocking.add(SKILL_NOT_MAPPED);
        }
        if (!input.hardConstraintsPass()) {
            blocking.add(HARD_CONSTRAINT_FAILED);
        }
        if (input.qualityScore() < properties.autoAssignQualityThreshold()) {
            blocking.add(QUALITY_BELOW_THRESHOLD);
        }
        if (input.pessimisticOptimisticRatio() > properties.maxPessimisticOptimisticRatio()) {
            blocking.add(ESTIMATE_INTERVAL_TOO_WIDE);
        }
        return new AdmissionDecision(blocking.isEmpty(), List.copyOf(blocking));
    }
}
