package com.example.transfers.acceptance;

import static com.example.transfers.support.KafkaTestSupport.CURRENT_ACCOUNT_EVENTS;
import static com.example.transfers.support.KafkaTestSupport.CURRENT_ACCOUNT_INSTRUCTIONS;
import static com.example.transfers.support.KafkaTestSupport.SAVINGS_EVENTS;
import static com.example.transfers.support.KafkaTestSupport.SAVINGS_INSTRUCTIONS;
import static com.example.transfers.support.KafkaTestSupport.TRANSFER_EVENTS;
import static com.example.transfers.support.KafkaTestSupport.TRANSFER_REQUESTS;

import com.example.events.common.v1.Money;
import com.example.events.currentaccount.v1.CreditRejected;
import com.example.events.currentaccount.v1.CurrentAccountCredited;
import com.example.events.currentaccount.v1.CurrentAccountDebited;
import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.currentaccount.v1.CurrentAccountOpened;
import com.example.events.currentaccount.v1.DebitRejected;
import com.example.events.payments.v1.CurrentAccountInstruction;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.TransferEvent;
import com.example.events.payments.v1.TransferRequest;
import com.example.events.savings.v1.AccountEvent;
import com.example.events.savings.v1.AccountOpened;
import com.example.events.savings.v1.MoneyDeposited;
import com.example.events.savings.v1.MoneyWithdrawn;
import com.example.events.savings.v1.WithdrawalRejected;
import com.example.events.savings.v1.WithdrawalReturned;
import com.example.transfers.adapter.scheduling.AwaitingAccountsJob;
import com.example.transfers.support.KafkaTestSupport;
import com.example.transfers.support.MutableClock;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bridge Karate features call through {@code Java.type}: plays the savings and current account
 * ledgers and the requesters, and observes what the transfer service sends.
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

    public static void runAwaitingAccountsJob() {
        context.getBean(AwaitingAccountsJob.class).run();
    }

    /** The {@code transfers.stuck} gauge, as Prometheus would read it now. */
    public static long stuckTransfers() {
        return (long) context.getBean(MeterRegistry.class).get("transfers.stuck").gauge().value();
    }

    // --- requesters -------------------------------------------------------------------------------

    /** A request straight on the command log, as savings sends one when an order opens an account. */
    public static void requestTransfer(String transferId, String customerId, String from, String to, String amount) {
        requestTransfer(transferId, customerId, from, to, amount, now());
    }

    /** A request that was sent at {@code isoInstant} and is only delivered now, e.g. after a backlog. */
    public static void requestTransferSentAt(String transferId, String customerId, String from, String to,
            String amount, String isoInstant) {
        var sent = Instant.parse(isoInstant);
        requestTransfer(transferId, customerId, from, to, amount,
                Timestamp.newBuilder().setSeconds(sent.getEpochSecond()).setNanos(sent.getNano()).build());
    }

    private static void requestTransfer(String transferId, String customerId, String from, String to, String amount,
            Timestamp occurredAt) {
        var request = TransferRequest.newBuilder()
                .setRequestId(uuid())
                .setOccurredAt(occurredAt)
                .setTransferId(transferId)
                .setCustomerId(customerId)
                .setFromAccountId(from)
                .setToAccountId(to)
                .setAmount(eur(amount))
                .build();
        kafka().send(TRANSFER_REQUESTS, transferId, request.toByteArray());
    }

    // --- the current account ledger ---------------------------------------------------------------

    /** @return the new account's id */
    public static String currentAccountOpened(String customerId) {
        var accountId = uuid();
        currentAccount(accountId, CurrentAccountEvent.newBuilder()
                .setCurrentAccountOpened(CurrentAccountOpened.newBuilder().setCustomerId(customerId)));
        return accountId;
    }

    /** Waits for an instruction (DEBIT_CURRENT_ACCOUNT or CREDIT_CURRENT_ACCOUNT) for the account. */
    public static Map<String, Object> awaitCurrentAccountInstruction(String accountId, String type) {
        var instruction = kafka().await(CURRENT_ACCOUNT_INSTRUCTIONS, CurrentAccountInstruction.parser(),
                i -> i.getAccountId().equals(accountId) && i.getPayloadCase().name().equals(type),
                type + " for current account " + accountId);
        return payload(instruction, instruction.getPayloadCase().getNumber());
    }

    public static void currentAccountDebited(String accountId, String transferId, String amount) {
        currentAccount(accountId, CurrentAccountEvent.newBuilder().setCurrentAccountDebited(
                CurrentAccountDebited.newBuilder().setTransferId(transferId).setAmount(eur(amount))));
    }

    public static void currentAccountDebitRejected(String accountId, String transferId, String reason) {
        currentAccount(accountId, CurrentAccountEvent.newBuilder().setDebitRejected(
                DebitRejected.newBuilder().setTransferId(transferId).setReason(reason)));
    }

    public static void currentAccountCredited(String accountId, String transferId, String amount) {
        currentAccount(accountId, CurrentAccountEvent.newBuilder().setCurrentAccountCredited(
                CurrentAccountCredited.newBuilder().setTransferId(transferId).setAmount(eur(amount))));
    }

    public static void currentAccountCreditRejected(String accountId, String transferId, String reason) {
        currentAccount(accountId, CurrentAccountEvent.newBuilder().setCreditRejected(
                CreditRejected.newBuilder().setTransferId(transferId).setReason(reason)));
    }

    // --- the savings ledger -----------------------------------------------------------------------

    /** @return the new account's id */
    public static String savingsAccountOpened(String customerId) {
        var accountId = uuid();
        savingsAccountOpenedWithId(customerId, accountId);
        return accountId;
    }

    public static void savingsAccountOpenedWithId(String customerId, String accountId) {
        savings(accountId, AccountEvent.newBuilder().setAccountOpened(
                AccountOpened.newBuilder().setCustomerId(customerId).setProductCode("INSTANT_ACCESS")));
    }

    /** Waits for an instruction (TRANSFER_FROM_CURRENT_ACCOUNT, TRANSFER_TO_CURRENT_ACCOUNT, TRANSFER_RETURNED). */
    public static Map<String, Object> awaitSavingsInstruction(String accountId, String type) {
        var instruction = kafka().await(SAVINGS_INSTRUCTIONS, PaymentInstruction.parser(),
                i -> i.getAccountId().equals(accountId) && i.getPayloadCase().name().equals(type),
                type + " for savings account " + accountId);
        return payload(instruction, instruction.getPayloadCase().getNumber());
    }

    public static void savingsDeposited(String accountId, String transferId, String amount) {
        savings(accountId, AccountEvent.newBuilder().setMoneyDeposited(MoneyDeposited.newBuilder()
                .setTransferId(transferId).setAmount(eur(amount)).setBalance(eur(amount))));
    }

    public static void savingsWithdrawn(String accountId, String transferId, String amount) {
        savings(accountId, AccountEvent.newBuilder().setMoneyWithdrawn(MoneyWithdrawn.newBuilder()
                .setTransferId(transferId).setAmount(eur(amount)).setBalance(eur("0.00"))));
    }

    public static void savingsWithdrawalRejected(String accountId, String transferId, String amount) {
        savings(accountId, AccountEvent.newBuilder().setWithdrawalRejected(WithdrawalRejected.newBuilder()
                .setTransferId(transferId).setAmount(eur(amount))
                .setReason(WithdrawalRejected.Reason.INSUFFICIENT_FUNDS)));
    }

    public static void savingsWithdrawalReturned(String accountId, String transferId, String amount) {
        savings(accountId, AccountEvent.newBuilder().setWithdrawalReturned(WithdrawalReturned.newBuilder()
                .setTransferId(transferId).setAmount(eur(amount)).setBalance(eur(amount))));
    }

    // --- observing the outcome --------------------------------------------------------------------

    /** Waits for the transfer's outcome; the map has {@code type} (TRANSFER_COMPLETED or TRANSFER_FAILED). */
    public static Map<String, Object> awaitTransferEvent(String transferId) {
        var event = kafka().await(TRANSFER_EVENTS, TransferEvent.parser(), e -> e.getTransferId().equals(transferId),
                "outcome of transfer " + transferId);
        var map = payload(event, event.getPayloadCase().getNumber());
        map.put("type", event.getPayloadCase().name());
        return map;
    }

    public static String awaitDeadLetter(String topic, String key) {
        var record = kafka().awaitDeadLetter(topic, key);
        var header = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        return header == null ? "" : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static void currentAccount(String accountId, CurrentAccountEvent.Builder event) {
        var built = event.setEventId(uuid()).setOccurredAt(now()).setAccountId(accountId).build();
        kafka().send(CURRENT_ACCOUNT_EVENTS, accountId, built.toByteArray());
    }

    private static void savings(String accountId, AccountEvent.Builder event) {
        var built = event.setEventId(uuid()).setOccurredAt(now()).setAccountId(accountId).build();
        kafka().send(SAVINGS_EVENTS, accountId, built.toByteArray());
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
