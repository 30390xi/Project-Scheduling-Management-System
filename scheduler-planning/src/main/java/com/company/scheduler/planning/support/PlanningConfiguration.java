package com.company.scheduler.planning.support;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared planning clock; tests replace it with a fixed clock. */
@Configuration
public class PlanningConfiguration {
    @Bean
    public Clock planningClock() {
        return Clock.systemUTC();
    }
}
