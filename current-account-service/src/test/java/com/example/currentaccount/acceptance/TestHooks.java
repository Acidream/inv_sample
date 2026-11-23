package com.example.currentaccount.acceptance;

import static com.example.currentaccount.support.KafkaTestSupport.COMMANDS;
import static com.example.currentaccount.support.KafkaTestSupport.EVENTS;
import static com.example.currentaccount.support.KafkaTestSupport.INSTRUCTIONS;

import com.example.currentaccount.support.KafkaTestSupport;
import com.example.currentaccount.support.MutableClock;
import com.example.events.common.v1.Money;
import com.example.events.currentaccount.v1.CurrentAccountCommand;
import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.currentaccount.v1.OpenCurrentAccount;
import com.example.events.currentaccount.v1.ReceivePayment;
import com.example.events.payments.v1.CreditCurrentAccount;
import com.example.events.payments.v1.CurrentAccountInstruction;
import com.example.events.payments.v1.DebitCurrentAccount;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bridge Karate features call through {@code Java.type}: plays the transfer service and observes
 * what the current account publishes.
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

    public static void setClock(String isoInstant) {
        context.getBean(MutableClock.class).set(Instant.parse(isoInstant));
    }

    public static void resetClock() {
        context.getBean(MutableClock.class).reset();
    }

    // --- the transfer service ---------------------------------------------------------------------

    /** @return the instruction's event id, to send it again as a redelivery */
    public static String debit(String accountId, String transferId, String amount) {
        return instruct(uuid(), accountId, CurrentAccountInstruction.newBuilder().setDebitCurrentAccount(
                DebitCurrentAccount.newBuilder().setTransferId(transferId).setAmount(eur(amount))));
    }

    /** @return the instruction's event id, to send it again as a redelivery */
    public static String credit(String accountId, String transferId, String amount) {
        return instruct(uuid(), accountId, CurrentAccountInstruction.newBuilder().setCreditCurrentAccount(
                CreditCurrentAccount.newBuilder().setTransferId(transferId).setAmount(eur(amount))));
    }

    /** The same debit message once more: same event id, as Kafka redelivers it. */
    public static void redeliverDebit(String eventId, String accountId, String transferId, String amount) {
        instruct(eventId, accountId, CurrentAccountInstruction.newBuilder().setDebitCurrentAccount(
                DebitCurrentAccount.newBuilder().setTransferId(transferId).setAmount(eur(amount))));
    }

    public static void sendGarbageInstruction(String accountId) {
        kafka().send(INSTRUCTIONS, accountId, new byte[] {(byte) 0xff, 0x01, 0x02});
    }

    /** A command straight on the command log, with a payment id longer than the database allows. */
    public static void sendPaymentWithOverlongId(String accountId) {
        var command = CurrentAccountCommand.newBuilder()
                .setCommandId(uuid())
                .setOccurredAt(now())
                .setAccountId(accountId)
                .setReceivePayment(ReceivePayment.newBuilder().setPaymentId("p".repeat(101)).setAmount(eur("1.00")))
                .build();
        kafka().send(COMMANDS, accountId, command.toByteArray());
    }

    /** An opening straight on the command log, as if accepted before an earlier one was applied. */
    public static void sendOpening(String accountId, String customerId, String overdraftLimit) {
        var command = CurrentAccountCommand.newBuilder()
                .setCommandId(uuid())
                .setOccurredAt(now())
                .setAccountId(accountId)
                .setOpenCurrentAccount(OpenCurrentAccount.newBuilder()
                        .setCustomerId(customerId)
                        .setOverdraftLimit(eur(overdraftLimit)))
                .build();
        kafka().send(COMMANDS, accountId, command.toByteArray());
    }

    // --- observing --------------------------------------------------------------------------------

    /**
     * Waits for, and consumes, the account's next event. The map holds the payload's fields plus
     * {@code type} (e.g. CURRENT_ACCOUNT_DEBITED) and {@code occurredAt}.
     */
    public static Map<String, Object> awaitEvent(String accountId) {
        var event = kafka().await(EVENTS, CurrentAccountEvent.parser(), e -> e.getAccountId().equals(accountId),
                "event for current account " + accountId);
        var map = payload(event, event.getPayloadCase().getNumber());
        map.put("type", event.getPayloadCase().name());
        var at = event.getOccurredAt();
        map.put("occurredAt", Instant.ofEpochSecond(at.getSeconds(), at.getNanos()).toString());
        return map;
    }

    /** @return the exception class that dead-lettered it */
    public static String awaitDeadLetter(String topic, String key) {
        var record = kafka().awaitDeadLetter(topic, key);
        var header = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static String instruct(String eventId, String accountId, CurrentAccountInstruction.Builder instruction) {
        var built = instruction.setEventId(eventId).setOccurredAt(now()).setAccountId(accountId).build();
        kafka().send(INSTRUCTIONS, accountId, built.toByteArray());
        return eventId;
    }

    /** The oneof payload of an envelope as a JSON-like map. */
    private static Map<String, Object> payload(Message envelope, int fieldNumber) {
        var field = envelope.getDescriptorForType().findFieldByNumber(fieldNumber);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(JsonFormat.printer().print((Message) envelope.getField(field)),
                    Map.class);
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
