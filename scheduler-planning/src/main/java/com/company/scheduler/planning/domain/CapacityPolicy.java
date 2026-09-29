package com.company.scheduler.planning.domain;

/** Pure domain calculation; database occupancy and atomic commit are separate future work. */
public final class CapacityPolicy {
    public static final int SLOT_MINUTES = 30;
    private CapacityPolicy() {}

    public static int effectiveCapacity(boolean workday, int standard, int dailyLimit,
                                        int leave, int extraUnavailable) {
        if (standard < 0 || dailyLimit < 0 || dailyLimit > standard || leave < 0
                || leave > standard || extraUnavailable < 0) {
            throw new IllegalArgumentException("Invalid capacity policy");
        }
        if (!workday) return 0;
        int available = Math.max(0, Math.min(dailyLimit, standard - leave) - extraUnavailable);
        return available / SLOT_MINUTES * SLOT_MINUTES;
    }

    public static int roundEstimateUp(int minutes) {
        if (minutes <= 0) throw new IllegalArgumentException("Estimate must be positive");
        return Math.toIntExact(((long) minutes + SLOT_MINUTES - 1) / SLOT_MINUTES * SLOT_MINUTES);
    }
}
