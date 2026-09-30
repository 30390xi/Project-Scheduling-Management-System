package com.company.scheduler.agent.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * 三点估算与计划工时（PRD §7 / TRD 7.5）。
 * 全程整数分钟 + BigDecimal 中间值，最终向上取整到 30 分钟步长。
 */
@Service
public class EstimationService {

    /** 向上取整到 30 分钟步长。 */
    public static int ceil30(long minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("minutes must be >= 0");
        }
        return (int) (((minutes + 29) / 30) * 30);
    }

    public record Computation(
            int optimisticMinutes,
            int likelyMinutes,
            int pessimisticMinutes,
            BigDecimal baseMinutes,
            int plannedMinutesWithBuffer) {}

    /** base = (o + 4l + p) / 6；planned = ceil30(base * 校准系数1.0 * (1+bufferRatio))。 */
    public Computation compute(AiAnalysisOutput.Estimate estimate, double bufferRatio) {
        if (estimate == null || estimate.optimisticMinutes() == null || estimate.likelyMinutes() == null
                || estimate.pessimisticMinutes() == null) {
            throw new IllegalArgumentException("estimate requires all three point minutes");
        }
        long optimistic = estimate.optimisticMinutes();
        long likely = estimate.likelyMinutes();
        long pessimistic = estimate.pessimisticMinutes();
        if (optimistic <= 0 || optimistic > likely || likely > pessimistic) {
            throw new IllegalArgumentException("estimate must satisfy 0 < optimistic <= likely <= pessimistic");
        }
        BigDecimal base = BigDecimal.valueOf(optimistic)
                .add(BigDecimal.valueOf(likely).multiply(BigDecimal.valueOf(4)))
                .add(BigDecimal.valueOf(pessimistic))
                .divide(BigDecimal.valueOf(6), 4, RoundingMode.HALF_UP);
        BigDecimal buffered = base.multiply(BigDecimal.valueOf(1 + bufferRatio));
        long planned = buffered.setScale(0, RoundingMode.CEILING).longValueExact();
        return new Computation((int) optimistic, (int) likely, (int) pessimistic,
                base, ceil30(planned));
    }

    /** 人工确认最终工时：直接取整，不再叠加缓冲（PRD §7.4）。 */
    public int manualFinalMinutes(long finalMinutes) {
        if (finalMinutes <= 0) {
            throw new IllegalArgumentException("final minutes must be positive");
        }
        return ceil30(finalMinutes);
    }
}
