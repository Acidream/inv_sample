package com.example.savings.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full application on a random port against Testcontainers. One shared Spring context for all
 * integration tests (same configuration = cached context). Cron jobs are switched off; tests
 * trigger them explicitly after moving the clock.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "savings.jobs.interest-accrual-cron=-",
    "savings.outbox.poll-interval-ms=50"
})
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
