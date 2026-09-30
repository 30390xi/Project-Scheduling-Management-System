package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EstimationServiceTest {

    private final EstimationService estimation = new EstimationService();

    @Test
    void threePointBaseAndBufferMatchTrdExample() {
        // TRD 7.5：180/240/360 → base 250 分钟，含 20% 缓冲向上取整 300 分钟
        var result = estimation.compute(
                new AiAnalysisOutput.Estimate(180, 240, 360, "采用已有组件"), 0.20);
        assertThat(result.baseMinutes().doubleValue()).isEqualTo(250.0);
        assertThat(result.plannedMinutesWithBuffer()).isEqualTo(300);
    }

    @Test
    void roundsUpToNextThirtyMinuteSlot() {
        // base 233.33 * 1.2 = 280 → ceil30 = 300
        var result = estimation.compute(
                new AiAnalysisOutput.Estimate(150, 230, 320, "b"), 0.20);
        assertThat(result.plannedMinutesWithBuffer()).isEqualTo(300);
    }

    @Test
    void zeroBufferIsExactCeil30() {
        var result = estimation.compute(
                new AiAnalysisOutput.Estimate(60, 90, 120, "b"), 0.0);
        assertThat(result.baseMinutes().doubleValue()).isEqualTo(90.0);
        assertThat(result.plannedMinutesWithBuffer()).isEqualTo(90);
    }

    @Test
    void ceil30AlwaysStepsByHalfHour() {
        assertThat(EstimationService.ceil30(1)).isEqualTo(30);
        assertThat(EstimationService.ceil30(30)).isEqualTo(30);
        assertThat(EstimationService.ceil30(31)).isEqualTo(60);
        assertThat(EstimationService.ceil30(619)).isEqualTo(630);
        assertThatThrownBy(() -> EstimationService.ceil30(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manualFinalDoesNotApplyBufferTwice() {
        // 人工确认最终工时：直接取整，不再叠加缓冲（PRD 7.4）
        assertThat(estimation.manualFinalMinutes(200)).isEqualTo(210);
        assertThat(estimation.manualFinalMinutes(240)).isEqualTo(240);
        assertThatThrownBy(() -> estimation.manualFinalMinutes(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnorderedEstimates() {
        assertThatThrownBy(() -> estimation.compute(
                new AiAnalysisOutput.Estimate(300, 200, 360, "b"), 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> estimation.compute(
                new AiAnalysisOutput.Estimate(null, 200, 360, "b"), 0.2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
