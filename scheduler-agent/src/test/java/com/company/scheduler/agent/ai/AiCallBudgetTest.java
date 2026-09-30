package com.company.scheduler.agent.ai;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AiCallBudgetTest {

    private final Clock clock = Clock.systemUTC();

    @Test
    void rejectsCallsBeyondLimit() {
        var budget = new AiCallBudget(2, Instant.now(clock).plusSeconds(60), clock);
        budget.consume();
        budget.consume();
        var exceeded = catchThrowable(budget::consume);
        assertThat(exceeded).isInstanceOf(AiCallBudget.AiBudgetExceededException.class)
                .hasFieldOrPropertyWithValue("errorCode", "AI_BUDGET_EXCEEDED");
    }

    @Test
    void rejectsCallsPastDeadline() {
        var budget = new AiCallBudget(4, Instant.now(clock).minusSeconds(1), clock);
        var timedOut = catchThrowable(budget::consume);
        assertThat(timedOut).isInstanceOf(AiCallBudget.AiBudgetExceededException.class)
                .hasFieldOrPropertyWithValue("errorCode", "AI_RUN_TIMEOUT");
        assertThat(budget.hasCapacity()).isFalse();
    }

    @Test
    void countsUsageAndReportsCapacity() {
        var budget = new AiCallBudget(3, Instant.now(clock).plusSeconds(60), clock);
        assertThat(budget.hasCapacity()).isTrue();
        budget.consume();
        assertThat(budget.used()).isEqualTo(1);
        assertThat(budget.hasCapacity()).isTrue();
    }

    @Test
    void ofUsesPropertiesDefaults() {
        var properties = AiGatewayProperties.defaults();
        var budget = AiCallBudget.of(properties, Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));
        assertThat(budget.maxCalls()).isEqualTo(4);
        assertThat(budget.deadline()).isEqualTo(Instant.parse("2026-09-30T00:03:00Z"));
        assertThat(Duration.between(Instant.parse("2026-09-30T00:00:00Z"), budget.deadline()))
                .isEqualTo(Duration.ofSeconds(180));
    }
}
