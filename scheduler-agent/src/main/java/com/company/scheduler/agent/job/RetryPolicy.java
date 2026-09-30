package com.company.scheduler.agent.job;

import java.time.Duration;
import java.time.Instant;

/**
 * 纯函数重试策略：指数退避 + 抖动，封顶 maxDelay。
 * 抖动用 (attempt + nanoTime 派生) 的确定性测试入口，生产使用随机源。
 */
public final class RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryPolicy(Duration baseDelay, Duration maxDelay) {
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    /** @param attempts 已执行尝试次数（1 表示第一次失败后） */
    public Duration nextDelay(int attempts, Instant jitterSource) {
        long backoffMillis;
        try {
            backoffMillis = Math.multiplyExact(baseDelay.toMillis(), 1L << Math.min(attempts - 1, 16));
        } catch (ArithmeticException overflow) {
            backoffMillis = Long.MAX_VALUE;
        }
        long capped = Math.min(backoffMillis, maxDelay.toMillis());
        // 0..25% 抖动，来源于时钟纳秒，测试中可用固定 Instant 稳定复现
        long jitter = Math.floorMod(jitterSource == null ? 0L : jitterSource.getNano() / 40, 1 + capped / 4);
        return Duration.ofMillis(capped + jitter);
    }
}
