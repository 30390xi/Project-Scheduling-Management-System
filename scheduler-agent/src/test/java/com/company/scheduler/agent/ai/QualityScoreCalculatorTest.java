package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import com.company.scheduler.agent.AgentJson;
import org.junit.jupiter.api.Test;

class QualityScoreCalculatorTest {

    private final QualityScoreCalculator calculator = new QualityScoreCalculator();

    @Test
    void weightsFollowTrdFormula() {
        var output = AgentJson.fromJson(AiSamples.VALID_SINGLE_LEAF, AiAnalysisOutput.class);
        var components = calculator.evaluate(output, true);
        // completeness：6 项中 5 项满足（无假设说明 basis 存在、assumptions 存在、子任务为空视为完整）
        assertThat(components.completeness()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
        assertThat(components.weighted()).isGreaterThan(0).isLessThanOrEqualTo(1.0);
        assertThat(components.coldStart()).isFalse();
        // 区间稳定性：360/180 = 2.0 → 1 - 1/1.5 ≈ 0.333
        assertThat(components.intervalStability()).isCloseTo(1 - (2.0 - 1) / 1.5, within(1e-9));
    }

    @Test
    void coldStartEvidenceDefaultsToHalfAndIsFlagged() {
        var output = AgentJson.fromJson(AiSamples.VALID_SINGLE_LEAF, AiAnalysisOutput.class);
        var components = calculator.evaluate(output, false);
        assertThat(components.caseEvidence()).isEqualTo(0.5);
        assertThat(components.coldStart()).isTrue();
    }

    @Test
    void widerIntervalsReduceStabilityToZero() {
        double ratio25 = calculator.evaluateIntervalStability(
                new AiAnalysisOutput.Estimate(100, 150, 250, "b"));
        assertThat(ratio25).isEqualTo(0.0);
        double ratio11 = calculator.evaluateIntervalStability(
                new AiAnalysisOutput.Estimate(100, 110, 110, "b"));
        assertThat(ratio11).isCloseTo(1 - 0.1 / 1.5, within(1e-9));
        double perfect = calculator.evaluateIntervalStability(
                new AiAnalysisOutput.Estimate(100, 100, 100, "b"));
        assertThat(perfect).isEqualTo(1.0);
    }

    @Test
    void missingEstimateScoresZeroStability() {
        assertThat(calculator.evaluateIntervalStability(null)).isEqualTo(0.0);
        assertThat(calculator.evaluateIntervalStability(
                new AiAnalysisOutput.Estimate(null, 100, 200, "b"))).isEqualTo(0.0);
    }
}
