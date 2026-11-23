package com.example.savings.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.savings.support.IntegrationTest;
import com.example.savings.support.KafkaTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A poison message must not block the partition: it goes to the dead-letter topic untouched. */
@IntegrationTest
class DeadLetterIT {

    @Autowired
    KafkaTestSupport kafka;

    @Autowired
    MeterRegistry registry;

    @Test
    void onMessage_UnparseableMessage_IsDeadLetteredWithoutRetriesAndCounted() {
        var key = UUID.randomUUID().toString();
        var garbage = "definitely not protobuf".getBytes(StandardCharsets.UTF_8);
        var before = deadLettered();

        kafka.send(key, garbage);

        var deadLetter = kafka.awaitDeadLetter(key);
        assertThat(deadLetter.value()).isEqualTo(garbage);
        assertThat(new String(deadLetter.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(),
                StandardCharsets.UTF_8)).contains("InvalidProtocolBufferException");
        // The counter is incremented right after the publish returns; the alert is built on it.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(deadLettered()).isGreaterThan(before));
    }

    private double deadLettered() {
        return registry.get("messages.dead.lettered").tag("topic", KafkaTestSupport.INSTRUCTIONS).counter()
                .count();
    }
}
