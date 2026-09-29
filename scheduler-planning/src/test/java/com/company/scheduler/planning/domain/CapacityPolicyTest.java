package com.company.scheduler.planning.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CapacityPolicyTest {
    @Test
    void deductsLeaveWithoutDoubleCountingDailyBuffer() {
        assertThat(CapacityPolicy.effectiveCapacity(true, 480, 360, 240, 60)).isEqualTo(180);
        assertThat(CapacityPolicy.effectiveCapacity(true, 480, 360, 0, 0)).isEqualTo(360);
    }

    @Test
    void roundsCapacityDownAndEffortUp() {
        assertThat(CapacityPolicy.effectiveCapacity(true, 480, 359, 0, 0)).isEqualTo(330);
        assertThat(CapacityPolicy.roundEstimateUp(31)).isEqualTo(60);
        assertThat(CapacityPolicy.roundEstimateUp(30)).isEqualTo(30);
    }

    @Test
    void unavailableDaysAndExcessUnavailabilityHaveZeroCapacity() {
        assertThat(CapacityPolicy.effectiveCapacity(false, 480, 360, 0, 0)).isZero();
        assertThat(CapacityPolicy.effectiveCapacity(true, 480, 360, 480, 0)).isZero();
        assertThat(CapacityPolicy.effectiveCapacity(true, 480, 360, 0, 600)).isZero();
    }

    @Test
    void rejectsInvalidValuesAndOverflow() {
        assertThatIllegalArgumentException().isThrownBy(() -> CapacityPolicy.effectiveCapacity(true, 480, 600, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> CapacityPolicy.roundEstimateUp(0));
        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> CapacityPolicy.roundEstimateUp(Integer.MAX_VALUE));
    }
}
