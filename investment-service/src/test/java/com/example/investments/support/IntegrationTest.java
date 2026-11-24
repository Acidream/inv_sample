package com.example.investments.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Full application on a random port against Testcontainers. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "platform.messaging.outbox.poll-interval-ms=50",
    "platform.messaging.retention.cron=-"
})
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
