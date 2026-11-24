package com.example.fixgateway.acceptance;

import static com.example.fixgateway.support.KafkaTestSupport.EXECUTIONS;
import static com.example.fixgateway.support.KafkaTestSupport.ORDERS;

import com.example.events.broker.v1.BrokerExecution;
import com.example.events.broker.v1.BrokerOrder;
import com.example.events.broker.v1.PlaceOrder;
import com.example.events.common.v1.Money;
import com.example.fixgateway.support.KafkaTestSupport;
import com.example.fixgateway.support.MutableClock;
import com.example.fixgateway.support.TestBroker;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bridge Karate features call through {@code Java.type}: plays investments on Kafka and the
 * broker over FIX ({@link TestBroker}), and observes what the gateway does.
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

    public static String orderId() {
        return "ord-" + uuid();
    }

    /** Today in Berlin, by the gateway's clock. */
    public static String today() {
        var now = context.getBean(MutableClock.class).instant();
        return LocalDate.ofInstant(now, ZoneId.of("Europe/Berlin")).toString();
    }

    public static void setClock(String isoInstant) {
        context.getBean(MutableClock.class).set(Instant.parse(isoInstant));
    }

    public static void resetClock() {
        context.getBean(MutableClock.class).reset();
    }

    public static void sleep(int millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    // --- investments ------------------------------------------------------------------------------

    /** @return the message id, to send the same message again as a redelivery */
    public static String buy(String orderId, String isin, String cashAmount, String tradingDay) {
        return place(uuid(), PlaceOrder.newBuilder().setOrderId(orderId).setIsin(isin).setSide(PlaceOrder.Side.BUY)
                .setCashAmount(Money.newBuilder().setAmount(cashAmount).setCurrency("EUR"))
                .setTradingDay(tradingDay));
    }

    public static String sell(String orderId, String isin, String quantity, String tradingDay) {
        return place(uuid(), PlaceOrder.newBuilder().setOrderId(orderId).setIsin(isin).setSide(PlaceOrder.Side.SELL)
                .setQuantity(quantity).setTradingDay(tradingDay));
    }

    /** The next report about the order; the map has {@code type}. */
    public static Map<String, Object> awaitExecution(String orderId) {
        var report = kafka().await(EXECUTIONS, BrokerExecution.parser(), r -> r.getOrderId().equals(orderId),
                "report of order " + orderId);
        var field = report.getDescriptorForType().findFieldByNumber(report.getPayloadCase().getNumber());
        var map = json((Message) report.getField(field));
        map.put("type", report.getPayloadCase().name());
        return map;
    }

    /** True if no further report about the order arrives within the time. */
    public static boolean noExecutionWithin(String orderId, int millis) {
        try {
            kafka().await(EXECUTIONS, BrokerExecution.parser(), r -> r.getOrderId().equals(orderId), "report",
                    java.time.Duration.ofMillis(millis));
            return false;
        } catch (AssertionError timedOut) {
            return true;
        }
    }

    public static String awaitDeadLetter(String topic, String key) {
        var record = kafka().awaitDeadLetter(topic, key);
        var header = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    // --- the broker -------------------------------------------------------------------------------

    /** The order's NewOrderSingle, its fields by tag number. */
    public static Map<String, String> awaitFixOrder(String orderId) {
        return broker().awaitOrder(orderId);
    }

    public static Map<String, String> awaitFixStatusRequest(String orderId) {
        return broker().awaitStatusRequest(orderId);
    }

    public static long fixOrdersReceived(String orderId) {
        return broker().ordersReceived(orderId);
    }

    public static void fixAccepted(String orderId) {
        broker().accept(orderId);
    }

    public static void fixFilled(String orderId, String execId, String quantity, String price, boolean done) {
        broker().fill(orderId, execId, quantity, price, done, false);
    }

    /** The same fill sent again with PossDupFlag, as after a resend request. */
    public static void fixFilledAgain(String orderId, String execId, String quantity, String price, boolean done) {
        broker().fill(orderId, execId, quantity, price, done, true);
    }

    public static void fixRejected(String orderId, String text, boolean duplicate) {
        broker().reject(orderId, text, duplicate);
    }

    public static void fixExpired(String orderId) {
        broker().expire(orderId);
    }

    public static void fixCancelled(String orderId, String text) {
        broker().cancel(orderId, text);
    }

    public static void brokerForgets(String orderId) {
        broker().forget(orderId);
    }

    public static void brokerDisconnects() throws IOException {
        broker().disconnect();
    }

    public static void brokerRefusesLogons(boolean refuse) {
        broker().refuseLogons(refuse);
    }

    public static boolean brokerLoggedOn() {
        return broker().isLoggedOn();
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static String place(String messageId, PlaceOrder.Builder order) {
        var message = BrokerOrder.newBuilder().setMessageId(messageId).setOccurredAt(now()).setPlaceOrder(order)
                .build();
        kafka().send(ORDERS, order.getOrderId(), message.toByteArray());
        return messageId;
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

    private static KafkaTestSupport kafka() {
        return context.getBean(KafkaTestSupport.class);
    }

    private static TestBroker broker() {
        return context.getBean(TestBroker.class);
    }
}
