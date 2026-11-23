package com.example.savings.adapter.messaging;

import com.example.platform.messaging.Outbox;
import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.AccountEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Account events for other teams, through the {@link Outbox}. */
@Component
class OutboxAccountEventPublisher implements AccountEventPublisher {

    private final Outbox outbox;
    private final Clock clock;
    private final String topic;

    OutboxAccountEventPublisher(Outbox outbox, Clock clock, SavingsProperties properties) {
        this.outbox = outbox;
        this.clock = clock;
        this.topic = properties.topics().accountEvents();
    }

    @Override
    public void publish(AccountEvent event) {
        var message = AccountEventMapper.toMessage(event, UUID.randomUUID().toString(), clock.instant());
        outbox.add(topic, event.accountId().toString(), message.getPayloadCase().name(), message.toByteArray());
    }
}
