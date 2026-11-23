package com.example.savings.acceptance;

import com.example.events.common.v1.Money;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.TransferFromCurrentAccount;
import com.example.events.payments.v1.TransferReturned;
import com.example.events.payments.v1.TransferCompleted;
import com.example.events.payments.v1.TransferEvent;
import com.example.events.payments.v1.TransferFailed;
import com.example.events.payments.v1.TransferToCurrentAccount;
import com.example.events.savings.v1.AccountEvent;
import com.example.events.savings.v1.Posting;
import com.example.savings.adapter.scheduling.InterestAccrualJob;
import com.example.savings.support.KafkaTestSupport;
import com.example.savings.support.MutableClock;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    // --- products ---------------------------------------------------------------------------------

    /**
     * Publishes a new product on sale, valid since long before any test clock, as a migration would.
     * Features use it to get exact terms for their arithmetic. @return the product code
     */
    public static String product(Object annualRate, String payoutFrequency) {
        var code = "TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbc().sql("insert into savings_product (code, name) values (:code, :name)")
                .param("code", code)
                .param("name", "Test product " + code)
                .update();
        productVersion(code, 1, annualRate, payoutFrequency, "2000-01-01T00:00:00Z");
        return code;
    }

    /** Publishes a further version of a product, taking effect at {@code validFrom} (ISO date-time with offset). */
    public static void productVersion(String code, int version, Object annualRate, String payoutFrequency,
            String validFrom) {
        jdbc().sql("insert into savings_product_version"
                        + " (product_code, version, annual_rate, payout_frequency, valid_from)"
                        + " values (:code, :version, :rate, :payout, :validFrom)")
                .param("code", code)
                .param("version", version)
                .param("rate", new BigDecimal(annualRate.toString()))
                .param("payout", payoutFrequency)
                .param("validFrom", OffsetDateTime.parse(validFrom))
                .update();
    }

    /** Takes a product off sale now (the service's clock): no new accounts, existing ones keep their terms. */
    public static void stopSelling(String code) {
        stopSellingFrom(code, context.getBean(MutableClock.class).instant().toString());
    }

    /** Takes a product off sale from {@code offSaleFrom} (ISO instant), as a migration would. */
    public static void stopSellingFrom(String code, String offSaleFrom) {
        jdbc().sql("update savings_product set off_sale_from = :from where code = :code")
                .param("from", Instant.parse(offSaleFrom).atOffset(ZoneOffset.UTC))
                .param("code", code)
                .update();
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

    // --- Payments team instructions ---------------------------------------------------------------

    /** Money from the current account; the current account has already been debited. */
    public static String deposit(String accountId, String amount) {
        return depositWithEventId(accountId, amount, UUID.randomUUID().toString());
    }

    /** Lets a feature deliver the very same message twice. */
    public static String depositWithEventId(String accountId, String amount, String eventId) {
        transferIn(accountId, eventId, "tr-" + eventId, amount);
        return eventId;
    }

    /** Lets a feature re-send a transfer as a new message, as Payments might after losing track of it. */
    public static void depositTransfer(String accountId, String transferId, String amount) {
        transferIn(accountId, UUID.randomUUID().toString(), transferId, amount);
    }

    /** Money to the current account. @return the generated transfer id */
    public static String withdraw(String accountId, String amount) {
        var transferId = "tr-" + UUID.randomUUID();
        withdrawTransfer(accountId, transferId, amount);
        return transferId;
    }

    public static void withdrawTransfer(String accountId, String transferId, String amount) {
        send(envelope(accountId).setTransferToCurrentAccount(TransferToCurrentAccount.newBuilder()
                .setTransferId(transferId)
                .setAmount(eur(amount))));
    }

    /** Compensation: the current account refused the withdrawal. */
    public static void returnTransfer(String accountId, String transferId) {
        send(envelope(accountId).setTransferReturned(TransferReturned.newBuilder().setTransferId(transferId)));
    }

    private static void transferIn(String accountId, String eventId, String transferId, String amount) {
        send(envelope(accountId, eventId).setTransferFromCurrentAccount(TransferFromCurrentAccount.newBuilder()
                .setTransferId(transferId)
                .setAmount(eur(amount))));
    }

    // --- the transfer service ---------------------------------------------------------------------

    /** Waits for the request savings sent to fund the account, as a JSON-like map. */
    public static Map<String, Object> awaitTransferRequest(String accountId) {
        return toMap(kafka().awaitTransferRequest(accountId));
    }

    public static void transferCompleted(String transferId) {
        sendTransferEvent(TransferEvent.newBuilder().setTransferId(transferId)
                .setTransferCompleted(TransferCompleted.getDefaultInstance()));
    }

    public static void transferFailed(String transferId, String reason, String detail) {
        sendTransferEvent(TransferEvent.newBuilder().setTransferId(transferId)
                .setTransferFailed(TransferFailed.newBuilder()
                        .setReason(TransferFailed.Reason.valueOf(reason))
                        .setDetail(detail)));
    }

    private static void sendTransferEvent(TransferEvent.Builder event) {
        var built = event.setEventId(UUID.randomUUID().toString())
                .setOccurredAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
                .build();
        kafka().send(KafkaTestSupport.TRANSFER_EVENTS, built.getTransferId(), built.toByteArray());
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

    /** Waits for the next posting of the given type (e.g. "DEPOSIT") for the account; see {@link #awaitPostings}. */
    public static Map<String, Object> awaitPosting(String accountId, String type) {
        return awaitPostings(accountId, type, 1).getFirst();
    }

    /**
     * Waits for the next {@code count} postings of the given type for the account, as JSON-like maps.
     * Every posting is checked to balance before a feature sees it: that is the contract with Finance.
     */
    public static List<Map<String, Object>> awaitPostings(String accountId, String type, int count) {
        return kafka().awaitPostings(accountId, Posting.Type.valueOf(type), count).stream()
                .map(TestHooks::balanced)
                .map(TestHooks::toMap)
                .toList();
    }

    /**
     * Waits until a message with this key was dead-lettered (an instruction: its account id; a transfer
     * event: its transfer id); returns the DLT exception class.
     */
    public static String awaitDeadLetter(String key) {
        var record = kafka().awaitDeadLetter(key);
        var header = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        return header == null ? "" : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static Posting balanced(Posting posting) {
        var debits = sum(posting, Posting.Line.Side.DEBIT);
        var credits = sum(posting, Posting.Line.Side.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new AssertionError("Unbalanced posting: debits " + debits + ", credits " + credits + ": " + posting);
        }
        return posting;
    }

    private static BigDecimal sum(Posting posting, Posting.Line.Side side) {
        return posting.getLinesList().stream()
                .filter(line -> line.getSide() == side)
                .map(line -> new BigDecimal(line.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Object> toMap(com.google.protobuf.Message message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(JsonFormat.printer().print(message), Map.class);
            return map;
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

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

    private static JdbcClient jdbc() {
        return context.getBean(JdbcClient.class);
    }

    private static KafkaTestSupport kafka() {
        return context.getBean(KafkaTestSupport.class);
    }
}
