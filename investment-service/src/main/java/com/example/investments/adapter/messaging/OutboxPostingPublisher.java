package com.example.investments.adapter.messaging;

import static com.example.platform.messaging.WireValues.timestamp;

import com.example.investments.application.port.PostingPublisher;
import com.example.investments.config.InvestmentProperties;
import com.example.investments.domain.Money;
import com.example.investments.domain.Posting;
import com.example.platform.messaging.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Postings for Finance (ADR 0014) through the shared {@link Outbox}, keyed by portfolio. */
@Component
class OutboxPostingPublisher implements PostingPublisher {

    private final Outbox outbox;
    private final String topic;

    OutboxPostingPublisher(Outbox outbox, InvestmentProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().postings();
    }

    @Override
    public void publish(Posting posting, Instant at) {
        var message = com.example.events.investments.v1.Posting.newBuilder()
                .setPostingId(UUID.randomUUID().toString())
                .setBookedAt(timestamp(at))
                .setValueDate(posting.valueDate().toString())
                .setPortfolioId(posting.portfolioId().toString())
                .setType(type(posting))
                .setReference(posting.reference())
                .setCurrency(Money.CURRENCY);
        for (var line : posting.lines()) {
            message.addLines(com.example.events.investments.v1.Posting.Line.newBuilder()
                    .setBookAccount(line.bookAccount().code())
                    .setSide(com.example.events.investments.v1.Posting.Line.Side.valueOf(line.side().name()))
                    .setAmount(line.amount().toString()));
        }
        var built = message.build();
        outbox.add(topic, built.getPortfolioId(), built.getType().name(), built.toByteArray());
    }

    private static com.example.events.investments.v1.Posting.Type type(Posting posting) {
        return switch (posting.type()) {
            case FUNDING -> com.example.events.investments.v1.Posting.Type.FUNDING;
            case BUY -> com.example.events.investments.v1.Posting.Type.BUY_FILL;
            case SELL -> com.example.events.investments.v1.Posting.Type.SELL_FILL;
            case PAYOUT -> com.example.events.investments.v1.Posting.Type.PAYOUT;
            case PAYOUT_RETURN -> com.example.events.investments.v1.Posting.Type.PAYOUT_RETURN;
        };
    }
}
