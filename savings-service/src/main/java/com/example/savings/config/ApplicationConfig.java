package com.example.savings.config;

import com.example.savings.application.SavingsPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Scheduling and ShedLock come with {@code platform-messaging} (ADR 0022). */
@Configuration
class ApplicationConfig {

    /** All "now" in the service comes from here, so tests can move time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SavingsPolicy savingsPolicy(SavingsProperties properties) {
        return new SavingsPolicy(properties.businessZone());
    }
}
