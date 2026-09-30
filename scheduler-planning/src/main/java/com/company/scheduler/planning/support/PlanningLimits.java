package com.company.scheduler.planning.support;

/** Phase 1 limits derived from TRD 18.1; wired to configuration properties when app config lands. */
public final class PlanningLimits {
    public static final int HORIZON_DAYS = 180;
    public static final int MAX_CAPACITY_VIEW_DAYS = 180;
    public static final int MAX_CALENDAR_BATCH = 400;

    private PlanningLimits() {}
}
