package com.example.savings.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.savings.support.IntegrationTest;
import com.example.savings.support.KafkaTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A poison message must not block the partition: it goes to the dead-letter topic untouched. */
@IntegrationTest
class DeadLetterIT {

    @Autowired
    KafkaTestSupport kafka;

    @Test
    void unparseableMessageIsDeadLetteredWithoutRetries() {
        var key = UUID.randomUUID().toString();
        var garbage = "definitely not protobuf".getBytes(StandardCharsets.UTF_8);

        kafka.send(key, garbage);

        var deadLetter = kafka.awaitDeadLetter(key);
        assertThat(deadLetter.value()).isEqualTo(garbage);
        assertThat(new String(deadLetter.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(),
                StandardCharsets.UTF_8)).contains("InvalidProtocolBufferException");
    }
}
