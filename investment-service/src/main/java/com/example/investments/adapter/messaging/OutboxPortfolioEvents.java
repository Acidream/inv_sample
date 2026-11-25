package com.example.investments.adapter.messaging;

import static com.example.investments.adapter.messaging.WireFormat.money;
import static com.example.platform.messaging.WireValues.timestamp;

import com.example.events.investments.v1.MoneyDeposited;
import com.example.events.investments.v1.MoneyWithdrawn;
import com.example.events.investments.v1.PortfolioEvent;
import com.example.events.investments.v1.PortfolioOpened;
import com.example.events.investments.v1.WithdrawalRejected;
import com.example.events.investments.v1.WithdrawalReturned;
import com.example.investments.application.port.PortfolioEvents;
import com.example.investments.config.InvestmentProperties;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.TransferAnswer;
import com.example.platform.messaging.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Portfolio events through the shared {@link Outbox} (ADR 0006), keyed by portfolio. */
@Component
class OutboxPortfolioEvents implements PortfolioEvents {

    private final Outbox outbox;
    private final String topic;

    OutboxPortfolioEvents(Outbox outbox, InvestmentProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().accountEvents();
    }

    @Override
    public void opened(Portfolio portfolio) {
        publish(event(portfolio.id(), portfolio.openedAt()).setPortfolioOpened(PortfolioOpened.newBuilder()
                .setCustomerId(portfolio.customerId().toString())
                .setReferenceAccountId(portfolio.referenceAccountId().toString())));
    }

    @Override
    public void answered(TransferAnswer answer) {
        var event = event(answer.portfolioId(), answer.answeredAt());
        var transferId = answer.transferId();
        var amount = money(answer.amount());
        publish(switch (answer.kind()) {
            case DEPOSIT -> event.setMoneyDeposited(MoneyDeposited.newBuilder()
                    .setTransferId(transferId).setAmount(amount));
            case WITHDRAWAL -> answer.carriedOut()
                    ? event.setMoneyWithdrawn(MoneyWithdrawn.newBuilder().setTransferId(transferId).setAmount(amount))
                    : event.setWithdrawalRejected(WithdrawalRejected.newBuilder()
                            .setTransferId(transferId).setAmount(amount).setReason(answer.rejectionReason()));
            case RETURN -> event.setWithdrawalReturned(WithdrawalReturned.newBuilder()
                    .setTransferId(transferId).setAmount(amount));
        });
    }

    private static PortfolioEvent.Builder event(UUID portfolioId, Instant at) {
        return PortfolioEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(at))
                .setPortfolioId(portfolioId.toString());
    }

    private void publish(PortfolioEvent.Builder event) {
        var built = event.build();
        outbox.add(topic, built.getPortfolioId(), built.getPayloadCase().name(), built.toByteArray());
    }
}
