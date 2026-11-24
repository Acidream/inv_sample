package com.example.investments.adapter.messaging;

import static com.example.investments.adapter.messaging.WireFormat.money;
import static com.example.investments.adapter.messaging.WireFormat.timestamp;

import com.example.events.broker.v1.BrokerOrder;
import com.example.events.broker.v1.PlaceOrder;
import com.example.events.investments.v1.MoneyDeposited;
import com.example.events.investments.v1.MoneyWithdrawn;
import com.example.events.investments.v1.PortfolioEvent;
import com.example.events.investments.v1.PortfolioOpened;
import com.example.events.investments.v1.WithdrawalRejected;
import com.example.events.investments.v1.WithdrawalReturned;
import com.example.events.payments.v1.TransferRequest;
import com.example.investments.application.port.Outbound;
import com.example.investments.config.InvestmentProperties;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.Posting;
import com.example.investments.domain.TransferAnswer;
import com.example.platform.messaging.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Everything investments sends, through the shared {@link Outbox} (ADR 0006): portfolio events keyed
 * by portfolio, transfer requests by transfer id, broker orders by order id, postings by portfolio.
 */
@Component
class OutboxOutbound implements Outbound {

    private final Outbox outbox;
    private final InvestmentProperties.Topics topics;

    OutboxOutbound(Outbox outbox, InvestmentProperties properties) {
        this.outbox = outbox;
        this.topics = properties.topics();
    }

    @Override
    public void portfolioOpened(Portfolio portfolio) {
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

    @Override
    public void requestTransfer(String transferId, UUID customerId, UUID fromAccountId, UUID toAccountId,
            Money amount, Instant at) {
        var request = TransferRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(at))
                .setTransferId(transferId)
                .setCustomerId(customerId.toString())
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount(money(amount))
                .build();
        outbox.add(topics.transferRequests(), transferId, "TRANSFER_REQUEST", request.toByteArray());
    }

    @Override
    public void placeWithBroker(Order order, Instant at) {
        var request = order.request();
        var place = PlaceOrder.newBuilder()
                .setOrderId(order.id())
                .setIsin(request.isin())
                .setSide(PlaceOrder.Side.valueOf(request.side().name()))
                .setTradingDay(order.tradingDay().orElseThrow().toString());
        if (request.side() == Order.Side.BUY) {
            place.setCashAmount(money(request.amount()));
        } else {
            place.setQuantity(request.quantity().toString());
        }
        var message = BrokerOrder.newBuilder()
                .setMessageId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(at))
                .setPlaceOrder(place)
                .build();
        outbox.add(topics.brokerOrders(), order.id(), "PLACE_ORDER", message.toByteArray());
    }

    @Override
    public void posting(Posting posting, Instant at) {
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
                    .setBookAccount(line.bookAccount())
                    .setSide(com.example.events.investments.v1.Posting.Line.Side.valueOf(line.side().name()))
                    .setAmount(line.amount().toString()));
        }
        var built = message.build();
        outbox.add(topics.postings(), built.getPortfolioId(), built.getType().name(), built.toByteArray());
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

    private static PortfolioEvent.Builder event(UUID portfolioId, Instant at) {
        return PortfolioEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(at))
                .setPortfolioId(portfolioId.toString());
    }

    private void publish(PortfolioEvent.Builder event) {
        var built = event.build();
        outbox.add(topics.accountEvents(), built.getPortfolioId(), built.getPayloadCase().name(), built.toByteArray());
    }
}
