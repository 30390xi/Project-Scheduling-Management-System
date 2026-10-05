package com.company.scheduler.agent.ai;

import java.time.Clock;
import java.time.Instant;

/**
 * 单次分析运行的调用预算（TRD 7.6）：最多 N 次外部调用 + 整体截止时间。
 * 生产预算从持久化运行恢复；每次调用先原子扣减数据库预算。
 */
public final class AiCallBudget {

    private final int maxCalls;
    private final Instant deadline;
    private final Clock clock;
    private int used;
    private final Runnable reserveCall;

    public AiCallBudget(int maxCalls, Instant deadline, Clock clock) {
        this(maxCalls, deadline, clock, 0, () -> {});
    }

    public AiCallBudget(int maxCalls, Instant deadline, Clock clock, int used, Runnable reserveCall) {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be >= 1");
        }
        this.maxCalls = maxCalls;
        this.deadline = deadline;
        this.clock = clock;
        this.used = used;
        this.reserveCall = reserveCall;
    }

    public static AiCallBudget of(AiGatewayProperties properties, Clock clock) {
        return new AiCallBudget(properties.maxProviderCallsPerRun(),
                Instant.now(clock).plus(properties.runDeadline()), clock);
    }

    public boolean hasCapacity() {
        return used < maxCalls && Instant.now(clock).isBefore(deadline);
    }

    /** 预算内占用一次调用；超限抛出，由编排层转为永久失败。 */
    public void consume() {
        if (used >= maxCalls) {
            throw new AiBudgetExceededException("AI_BUDGET_EXCEEDED",
                    "单次分析外部调用超过上限 " + maxCalls);
        }
        if (!Instant.now(clock).isBefore(deadline)) {
            throw new AiBudgetExceededException("AI_RUN_TIMEOUT",
                    "分析运行超过截止时间 " + deadline);
        }
        reserveCall.run();
        used++;
    }

    public int used() { return used; }

    public int maxCalls() { return maxCalls; }

    public Instant deadline() { return deadline; }

    public static final class AiBudgetExceededException extends RuntimeException {
        private final String errorCode;

        public AiBudgetExceededException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() { return errorCode; }
    }
}
