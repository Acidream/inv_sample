package com.example.savings.acceptance;

import com.example.events.common.v1.Money;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.PaymentReceived;
import com.example.events.payments.v1.WithdrawalCancelled;
import com.example.events.payments.v1.WithdrawalConfirmed;
import com.example.events.payments.v1.WithdrawalRequested;
import com.example.events.savings.v1.AccountEvent;
import com.example.savings.adapter.scheduling.HoldExpiryJob;
import com.example.savings.adapter.scheduling.InterestAccrualJob;
import com.example.savings.support.KafkaTestSupport;
import com.example.savings.support.MutableClock;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bridge Karate features call through {@code Java.type}. Static because Karate creates no
 * Spring beans; {@link #init} hands over the running application context.
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

    public static void runInterestJob() {
        context.getBean(InterestAccrualJob.class).run();
    }

    public static void runHoldExpiryJob() {
        context.getBean(HoldExpiryJob.class).run();
    }

    // --- Payments team instructions ---------------------------------------------------------------

    public static String deposit(String accountId, String amount) {
        return depositWithEventId(accountId, amount, UUID.randomUUID().toString());
    }

    /** Lets a feature deliver the very same message twice. */
    public static String depositWithEventId(String accountId, String amount, String eventId) {
        return sendPayment(accountId, eventId, "pay-" + eventId, amount);
    }

    /** Lets a feature re-send a payment as a new message, as Payments might after losing track of it. */
    public static String depositPayment(String accountId, String paymentId, String amount) {
        return sendPayment(accountId, UUID.randomUUID().toString(), paymentId, amount);
    }

    private static String sendPayment(String accountId, String eventId, String paymentId, String amount) {
        send(envelope(accountId, eventId).setPaymentReceived(PaymentReceived.newBuilder()
                .setPaymentId(paymentId)
                .setAmount(eur(amount))));
        return eventId;
    }

    /** @return the generated withdrawal request id */
    public static String requestWithdrawal(String accountId, String amount) {
        var requestId = "wd-" + UUID.randomUUID();
        send(envelope(accountId).setWithdrawalRequested(WithdrawalRequested.newBuilder()
                .setRequestId(requestId)
                .setAmount(eur(amount))));
        return requestId;
    }

    public static void confirmWithdrawal(String accountId, String requestId) {
        send(envelope(accountId).setWithdrawalConfirmed(WithdrawalConfirmed.newBuilder().setRequestId(requestId)));
    }

    public static void cancelWithdrawal(String accountId, String requestId) {
        send(envelope(accountId).setWithdrawalCancelled(WithdrawalCancelled.newBuilder().setRequestId(requestId)));
    }

    // --- observing Savings ------------------------------------------------------------------------

    /**
     * Waits for the next event of the given type (e.g. "MONEY_DEPOSITED") for the account and returns
     * its payload as JSON-like map, plus the envelope's eventId.
     */
    public static Map<String, Object> awaitEvent(String accountId, String type) {
        var event = kafka().awaitEvent(accountId, AccountEvent.PayloadCase.valueOf(type));
        try {
            var payloadField = event.getDescriptorForType().findFieldByNumber(event.getPayloadCase().getNumber());
            var payload = (com.google.protobuf.Message) event.getField(payloadField);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(JsonFormat.printer().print(payload), Map.class);
            map.put("eventId", event.getEventId());
            return map;
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the instruction with this account key was dead-lettered; returns the DLT exception class. */
    public static String awaitDeadLetter(String accountId) {
        var record = kafka().awaitDeadLetter(accountId);
        var header = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        return header == null ? "" : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static PaymentInstruction.Builder envelope(String accountId) {
        return envelope(accountId, UUID.randomUUID().toString());
    }

    private static PaymentInstruction.Builder envelope(String accountId, String eventId) {
        var now = Instant.now();
        return PaymentInstruction.newBuilder()
                .setEventId(eventId)
                .setOccurredAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(now.getEpochSecond()))
                .setAccountId(accountId);
    }

    private static Money eur(String amount) {
        return Money.newBuilder().setAmount(amount).setCurrency("EUR").build();
    }

    private static void send(PaymentInstruction.Builder message) {
        var built = message.build();
        kafka().send(built.getAccountId(), built.toByteArray());
    }

    private static KafkaTestSupport kafka() {
        return context.getBean(KafkaTestSupport.class);
    }
}
