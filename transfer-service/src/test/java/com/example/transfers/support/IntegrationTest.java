package com.example.transfers.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Full application on a random port against Testcontainers. Jobs are triggered by the tests. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "transfers.jobs.awaiting-accounts-cron=-",
    "transfers.outbox.poll-interval-ms=50"
})
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
