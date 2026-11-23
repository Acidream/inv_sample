package com.example.savings.adapter.messaging;

import com.example.events.savings.v1.Posting.Line;
import com.example.savings.application.port.PostingPublisher;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.Money;
import com.example.savings.domain.Posting;
import com.example.savings.domain.PostingLine;
import com.google.protobuf.Timestamp;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Double-entry postings for Finance, through the {@link Outbox}. */
@Component
class OutboxPostingPublisher implements PostingPublisher {

    private final Outbox outbox;
    private final String topic;

    OutboxPostingPublisher(Outbox outbox, SavingsProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().postings();
    }

    @Override
    public void publish(Posting posting) {
        var message = toMessage(posting, UUID.randomUUID().toString());
        outbox.add(topic, posting.accountId().toString(), "POSTING_" + posting.type(), message.toByteArray());
    }

    static com.example.events.savings.v1.Posting toMessage(Posting posting, String postingId) {
        var message = com.example.events.savings.v1.Posting.newBuilder()
                .setPostingId(postingId)
                .setBookedAt(Timestamp.newBuilder()
                        .setSeconds(posting.bookedAt().getEpochSecond())
                        .setNanos(posting.bookedAt().getNano()))
                .setValueDate(posting.valueDate().toString())
                .setAccountId(posting.accountId().toString())
                .setType(com.example.events.savings.v1.Posting.Type.valueOf(posting.type().name()))
                .setReference(posting.reference())
                .setCurrency(Money.CURRENCY);
        for (PostingLine line : posting.lines()) {
            message.addLines(Line.newBuilder()
                    .setBookAccount(line.account().code())
                    .setSide(Line.Side.valueOf(line.side().name()))
                    .setAmount(line.amount().toPlainString()));
        }
        return message.build();
    }
}
