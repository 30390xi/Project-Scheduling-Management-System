package com.company.scheduler.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 有效日合并的纯函数规则：周末推断、节假日、调休、半天假与额外不可用。 */
class EffectiveDayCalculatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    @Test
    void defaultsToWeekdaysAndDerivesCapacity() {
        assertThat(EffectiveDayCalculator.compute(MONDAY, null, null, null, 480, 360).capacityMinutes())
                .isEqualTo(360);
        assertThat(EffectiveDayCalculator.compute(SATURDAY, null, null, null, 480, 360).capacityMinutes())
                .isZero();
    }

    @Test
    void companyCalendarFlagOverridesWeekdayRule() {
        assertThat(EffectiveDayCalculator.compute(SATURDAY, false, 5L, null, 480, 360).capacityMinutes()).isZero();
        assertThat(EffectiveDayCalculator.compute(SUNDAY, true, 6L, null, 480, 360).capacityMinutes()).isEqualTo(360);
    }

    @Test
    void memberOverrideWinsOverCompanyCalendar() {
        var override = new EffectiveDayCalculator.MemberDayOverride(false, 0, 0, 9L);
        assertThat(EffectiveDayCalculator.compute(MONDAY, true, 1L, override, 480, 360).capacityMinutes()).isZero();
        var workSunday = new EffectiveDayCalculator.MemberDayOverride(true, 0, 0, 9L);
        assertThat(EffectiveDayCalculator.compute(SUNDAY, false, 2L, workSunday, 480, 360).capacityMinutes())
                .isEqualTo(360);
    }

    @Test
    void halfDayLeaveAndExtraUnavailableReduceCapacity() {
        var leave = new EffectiveDayCalculator.MemberDayOverride(null, 240, 0, 3L);
        assertThat(EffectiveDayCalculator.compute(MONDAY, null, null, leave, 480, 360).capacityMinutes())
                .isEqualTo(240);
        var extra = new EffectiveDayCalculator.MemberDayOverride(null, 0, 60, 3L);
        assertThat(EffectiveDayCalculator.compute(MONDAY, null, null, extra, 480, 360).capacityMinutes())
                .isEqualTo(300);
        var fullDay = new EffectiveDayCalculator.MemberDayOverride(null, 480, 0, 3L);
        assertThat(EffectiveDayCalculator.compute(MONDAY, null, null, fullDay, 480, 360).capacityMinutes())
                .isZero();
    }

    @Test
    void calendarVersionTakesTheMaxOfSources() {
        var override = new EffectiveDayCalculator.MemberDayOverride(null, 0, 0, 9L);
        assertThat(EffectiveDayCalculator.compute(MONDAY, true, 5L, override, 480, 360).calendarVersion())
                .isEqualTo(9L);
        assertThat(EffectiveDayCalculator.compute(MONDAY, true, 12L, override, 480, 360).calendarVersion())
                .isEqualTo(12L);
    }

    @Test
    void rejectsNegativeOrOverlongLeave() {
        var negative = new EffectiveDayCalculator.MemberDayOverride(null, -30, 0, 1L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EffectiveDayCalculator.compute(MONDAY, null, null, negative, 480, 360));
        var overlong = new EffectiveDayCalculator.MemberDayOverride(null, 510, 0, 1L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EffectiveDayCalculator.compute(MONDAY, null, null, overlong, 480, 360));
    }
}
