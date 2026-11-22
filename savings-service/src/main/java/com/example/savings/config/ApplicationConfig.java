package com.example.savings.config;

import com.example.savings.application.SavingsPolicy;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
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

    /** Scheduled jobs run on one instance at a time; the lock lives in Postgres and uses DB time. */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
