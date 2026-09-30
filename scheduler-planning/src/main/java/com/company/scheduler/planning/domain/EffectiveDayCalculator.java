package com.company.scheduler.planning.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 公司日历与成员覆盖合并为单个有效日的纯函数计算。
 * 无公司日历行时按周一至周五为工作日推断；成员 workday_flag 为 null 时沿用公司结论。
 */
public final class EffectiveDayCalculator {
    private EffectiveDayCalculator() {}

    public static boolean defaultWorkday(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    public static EffectiveDay compute(LocalDate date, Boolean companyWorkdayFlag, Long companyVersion,
                                       MemberDayOverride override, int standardMinutes, int dailyLimitMinutes) {
        boolean companyWorkday = companyWorkdayFlag != null ? companyWorkdayFlag : defaultWorkday(date);
        boolean workday = override != null && override.workdayFlag() != null ? override.workdayFlag() : companyWorkday;
        int leave = override == null ? 0 : override.leaveMinutes();
        int extraUnavailable = override == null ? 0 : override.extraUnavailableMinutes();
        long calendarVersion = Math.max(companyVersion == null ? 0 : companyVersion,
                override == null ? 0 : override.version());
        int capacity = CapacityPolicy.effectiveCapacity(workday, standardMinutes, dailyLimitMinutes, leave, extraUnavailable);
        return new EffectiveDay(date, workday, leave, extraUnavailable, calendarVersion, capacity);
    }

    public record MemberDayOverride(Boolean workdayFlag, int leaveMinutes, int extraUnavailableMinutes, long version) {}

    public record EffectiveDay(LocalDate workDate, boolean workday, int leaveMinutes, int extraUnavailableMinutes,
                               long calendarVersion, int capacityMinutes) {}
}
