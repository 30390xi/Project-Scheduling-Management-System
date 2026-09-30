package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConfirmationPolicyTest {

    private final ConfirmationPolicy policy = new ConfirmationPolicy(AiGatewayProperties.defaults());

    private ConfirmationPolicy.AdmissionInput.Builder base() {
        return new ConfirmationPolicy.AdmissionInput.Builder();
    }

    @Test
    void allGatesPassedAllowsAutoSubmit() {
        var decision = policy.decide(base().build());
        assertThat(decision.autoSubmitAllowed()).isTrue();
        assertThat(decision.blockingReasons()).isEmpty();
    }

    @Test
    void firstDecompositionAlwaysRequiresHumanEvenWhenEverythingElsePasses() {
        var decision = policy.decide(base().firstDecomposition(true).build());
        assertThat(decision.autoSubmitAllowed()).isFalse();
        assertThat(decision.blockingReasons())
                .contains(ConfirmationPolicy.FIRST_DECOMPOSITION_REQUIRES_HUMAN);
    }

    @Test
    void unconfirmedAssumptionsAndMissingAcceptanceBlockAuto() {
        var decision = policy.decide(base()
                .allKeyAssumptionsConfirmed(false)
                .acceptanceCriteriaPresent(false)
                .build());
        assertThat(decision.blockingReasons()).contains(
                ConfirmationPolicy.UNCONFIRMED_KEY_ASSUMPTIONS,
                ConfirmationPolicy.ACCEPTANCE_CRITERIA_MISSING);
    }

    @Test
    void lowQualityOrWideIntervalBlocksAuto() {
        assertThat(policy.decide(base().qualityScore(0.79).build()).blockingReasons())
                .contains(ConfirmationPolicy.QUALITY_BELOW_THRESHOLD);
        assertThat(policy.decide(base().pessimisticOptimisticRatio(2.6).build()).blockingReasons())
                .contains(ConfirmationPolicy.ESTIMATE_INTERVAL_TOO_WIDE);
        // 恰好在阈值上不阻断
        assertThat(policy.decide(base().qualityScore(0.80)
                .pessimisticOptimisticRatio(2.5).build()).autoSubmitAllowed()).isTrue();
    }

    @Test
    void illegalOutputOrDisabledProjectBlocksAuto() {
        assertThat(policy.decide(base().outputLegal(false).build()).blockingReasons())
                .contains(ConfirmationPolicy.AI_OUTPUT_INVALID);
        assertThat(policy.decide(base().projectAutoAssignEnabled(false).build()).blockingReasons())
                .contains(ConfirmationPolicy.AUTO_ASSIGN_DISABLED);
        assertThat(policy.decide(base().hardConstraintsPass(false).build()).blockingReasons())
                .contains(ConfirmationPolicy.HARD_CONSTRAINT_FAILED);
    }
}
