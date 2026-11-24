package com.example.investments.acceptance;

import static com.example.investments.support.KafkaTestSupport.ACCOUNT_EVENTS;
import static com.example.investments.support.KafkaTestSupport.BROKER_EXECUTIONS;
import static com.example.investments.support.KafkaTestSupport.BROKER_ORDERS;
import static com.example.investments.support.KafkaTestSupport.CURRENT_ACCOUNT_EVENTS;
import static com.example.investments.support.KafkaTestSupport.INSTRUCTIONS;
import static com.example.investments.support.KafkaTestSupport.POSTINGS;
import static com.example.investments.support.KafkaTestSupport.TRANSFER_EVENTS;
import static com.example.investments.support.KafkaTestSupport.TRANSFER_REQUESTS;

import com.example.events.broker.v1.BrokerExecution;
import com.example.events.broker.v1.BrokerOrder;
import com.example.events.broker.v1.OrderAccepted;
import com.example.events.broker.v1.OrderExpired;
import com.example.events.broker.v1.OrderFilled;
import com.example.events.broker.v1.OrderRejected;
import com.example.events.common.v1.Money;
import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.currentaccount.v1.CurrentAccountUnblocked;
import com.example.events.investments.v1.PortfolioEvent;
import com.example.events.investments.v1.Posting;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.TransferCompleted;
import com.example.events.payments.v1.TransferEvent;
import com.example.events.payments.v1.TransferFailed;
import com.example.events.payments.v1.TransferFromCurrentAccount;
import com.example.events.payments.v1.TransferRequest;
import com.example.events.payments.v1.TransferReturned;
import com.example.events.payments.v1.TransferToCurrentAccount;
import com.example.investments.adapter.scheduling.UnpaidPayoutJob;
import com.example.investments.support.KafkaTestSupport;
import com.example.investments.support.MutableClock;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bridge Karate features call through {@code Java.type}: plays the transfer service, the current
 * account and the broker gateway, and observes what the investment service publishes.
 */
public final class TestHooks {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static volatile ApplicationContext context;

    private TestHooks() {
    }

    static void init(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    // --- time and jobs ----------------------------------------------------------------------------

    public static void setClock(String isoInstant) {
        context.getBean(MutableClock.class).set(Instant.parse(isoInstant));
    }

    public static void resetClock() {
        context.getBean(MutableClock.class).reset();
    }

    public static void runUnpaidPayoutJob() {
        context.getBean(UnpaidPayoutJob.class).run();
    }

    /** A gauge, as Prometheus would read it now. */
    public static long gauge(String name) {
        return (long) context.getBean(MeterRegistry.class).get(name).gauge().value();
    }

    // --- observing the investment service ---------------------------------------------------------

    /** The next event of the portfolio; the map has {@code type}. */
    public static Map<String, Object> awaitPortfolioEvent(String portfolioId) {
        var event = kafka().await(ACCOUNT_EVENTS, PortfolioEvent.parser(), e -> e.getPortfolioId().equals(portfolioId),
                "event of portfolio " + portfolioId);
        return typed(event, event.getPayloadCase().getNumber(), event.getPayloadCase().name());
    }

    /** The next transfer request between these accounts; the map is the whole request. */
    public static Map<String, Object> awaitTransferRequest(String fromAccountId, String toAccountId) {
        var request = kafka().await(TRANSFER_REQUESTS, TransferRequest.parser(),
                r -> r.getFromAccountId().equals(fromAccountId) && r.getToAccountId().equals(toAccountId),
                "transfer request from " + fromAccountId + " to " + toAccountId);
        return json(request);
    }

    /** The order placed with the broker; the map is its PlaceOrder. */
    public static Map<String, Object> awaitBrokerOrder(String orderId) {
        var order = kafka().await(BROKER_ORDERS, BrokerOrder.parser(),
                o -> o.getPlaceOrder().getOrderId().equals(orderId), "broker order " + orderId);
        return json(order.getPlaceOrder());
    }

    /** The next posting of the portfolio for Finance. */
    public static Map<String, Object> awaitPosting(String portfolioId) {
        var posting = kafka().await(POSTINGS, Posting.parser(), p -> p.getPortfolioId().equals(portfolioId),
                "posting of portfolio " + portfolioId);
        return json(posting);
    }

    /** @return the exception class that dead-lettered it */
    public static String awaitDeadLetter(String topic, String key) {
        var record = kafka().awaitDeadLetter(topic, key);
        var header = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    // --- the transfer service ---------------------------------------------------------------------

    /** {@code TransferFromCurrentAccount}: the current account was debited, credit the portfolio. */
    public static String deposit(String portfolioId, String transferId, String amount) {
        return instruct(portfolioId, PaymentInstruction.newBuilder().setTransferFromCurrentAccount(
                TransferFromCurrentAccount.newBuilder().setTransferId(transferId).setAmount(eur(amount))));
    }

    /** {@code TransferToCurrentAccount}: take the money out for the current account. */
    public static String withdraw(String portfolioId, String transferId, String amount) {
        return instruct(portfolioId, PaymentInstruction.newBuilder().setTransferToCurrentAccount(
                TransferToCurrentAccount.newBuilder().setTransferId(transferId).setAmount(eur(amount))));
    }

    /** {@code TransferReturned}: the current account refused the credit. */
    public static String returnTransfer(String portfolioId, String transferId) {
        return instruct(portfolioId, PaymentInstruction.newBuilder().setTransferReturned(
                TransferReturned.newBuilder().setTransferId(transferId)));
    }

    public static void transferCompleted(String transferId) {
        transferEvent(transferId,
                TransferEvent.newBuilder().setTransferCompleted(TransferCompleted.getDefaultInstance()));
    }

    public static void transferFailed(String transferId, String reason, String detail) {
        transferEvent(transferId, TransferEvent.newBuilder().setTransferFailed(TransferFailed.newBuilder()
                .setReason(TransferFailed.Reason.valueOf(reason)).setDetail(detail)));
    }

    /**
     * Plays a whole payout the current account accepts: the withdrawal, its answer, and the outcome.
     *
     * @return the answer to the withdrawal
     */
    public static Map<String, Object> payOut(String portfolioId, String transferId, String amount) {
        withdraw(portfolioId, transferId, amount);
        var answer = awaitPortfolioEvent(portfolioId);
        transferCompleted(transferId);
        return answer;
    }

    // --- the current account ----------------------------------------------------------------------

    public static void currentAccountUnblocked(String accountId) {
        var event = CurrentAccountEvent.newBuilder()
                .setEventId(uuid()).setOccurredAt(now()).setAccountId(accountId)
                .setCurrentAccountUnblocked(CurrentAccountUnblocked.getDefaultInstance())
                .build();
        kafka().send(CURRENT_ACCOUNT_EVENTS, accountId, event.toByteArray());
    }

    // --- the broker gateway -----------------------------------------------------------------------

    public static void brokerAccepted(String orderId) {
        broker(orderId, BrokerExecution.newBuilder().setOrderAccepted(OrderAccepted.getDefaultInstance()));
    }

    public static void brokerFilled(String orderId, String execId, String quantity, String price, boolean done) {
        broker(orderId, BrokerExecution.newBuilder().setOrderFilled(OrderFilled.newBuilder()
                .setExecId(execId).setQuantity(quantity).setPrice(price).setDone(done)
                .setTradedAt(Timestamp.newBuilder().setSeconds(1_780_000_000L))));
    }

    public static void brokerRejected(String orderId, String reason) {
        broker(orderId, BrokerExecution.newBuilder().setOrderRejected(OrderRejected.newBuilder().setReason(reason)));
    }

    public static void brokerExpired(String orderId) {
        broker(orderId, BrokerExecution.newBuilder().setOrderExpired(OrderExpired.getDefaultInstance()));
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static String instruct(String portfolioId, PaymentInstruction.Builder instruction) {
        var id = uuid();
        var built = instruction.setEventId(id).setOccurredAt(now()).setAccountId(portfolioId).build();
        kafka().send(INSTRUCTIONS, portfolioId, built.toByteArray());
        return id;
    }

    private static void transferEvent(String transferId, TransferEvent.Builder event) {
        var built = event.setEventId(uuid()).setOccurredAt(now()).setTransferId(transferId).build();
        kafka().send(TRANSFER_EVENTS, transferId, built.toByteArray());
    }

    private static void broker(String orderId, BrokerExecution.Builder report) {
        var built = report.setEventId(uuid()).setOccurredAt(now()).setOrderId(orderId).build();
        kafka().send(BROKER_EXECUTIONS, orderId, built.toByteArray());
    }

    private static Map<String, Object> typed(Message envelope, int fieldNumber, String type) {
        var field = envelope.getDescriptorForType().findFieldByNumber(fieldNumber);
        var map = json((Message) envelope.getField(field));
        map.put("type", type);
        return map;
    }

    private static Map<String, Object> json(Message message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(JsonFormat.printer().print(message), Map.class);
            return map;
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp now() {
        var now = context.getBean(MutableClock.class).instant();
        return Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
    }

    private static Money eur(String amount) {
        return Money.newBuilder().setAmount(amount).setCurrency("EUR").build();
    }

    private static KafkaTestSupport kafka() {
        return context.getBean(KafkaTestSupport.class);
    }
}
