package com.example.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.ApplicationAdapter;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.field.AvgPx;
import quickfix.field.CashOrderQty;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.SecurityID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.fix44.ExecutionReport;

/**
 * Plays the broker for {@code docker compose} (ADR 0027): a FIX 4.4 acceptor that fills every market
 * order at once, in full, at a fixed price per ISIN from {@code prices.properties}. A buy for an
 * amount gets the whole units and fractions (6 digits) that amount pays for, never more; an unknown
 * ISIN or an order id seen before is rejected. It keeps nothing across restarts.
 */
public final class ExecutionSimulator extends ApplicationAdapter {

    private static final Logger log = LoggerFactory.getLogger(ExecutionSimulator.class);

    private final Map<String, BigDecimal> prices;
    private final Set<String> orders = ConcurrentHashMap.newKeySet();
    private final AtomicLong ids = new AtomicLong();

    ExecutionSimulator(Map<String, BigDecimal> prices) {
        this.prices = prices;
    }

    public static void main(String[] args) throws ConfigError, IOException, InterruptedException {
        var settings = new SessionSettings();
        var session = new SessionID("FIX.4.4", env("FIX_SENDER_COMP_ID", "BROKER"), env("FIX_TARGET_COMP_ID", "BANK"));
        settings.setString(session, "ConnectionType", "acceptor");
        settings.setString(session, "BeginString", "FIX.4.4");
        settings.setLong(session, "SocketAcceptPort", Long.parseLong(env("FIX_PORT", "9878")));
        settings.setString(session, "NonStopSession", "Y");
        settings.setString(session, "UseDataDictionary", "Y");
        settings.setString(session, "DataDictionary", "FIX44.xml");
        settings.setLong(session, "HeartBtInt", 30);
        var simulator = new ExecutionSimulator(prices());
        var acceptor = new SocketAcceptor(simulator, new MemoryStoreFactory(), settings, new SLF4JLogFactory(settings),
                new DefaultMessageFactory());
        acceptor.start();
        log.info("Execution simulator accepting FIX 4.4 on {} with prices {}", env("FIX_PORT", "9878"),
                simulator.prices);
        new CountDownLatch(1).await();
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound {
        var type = message.getHeader().getString(MsgType.FIELD);
        var orderId = message.getString(ClOrdID.FIELD);
        var side = message.getChar(Side.FIELD);
        if (MsgType.ORDER_STATUS_REQUEST.equals(type)) {
            var known = orders.contains(orderId);
            var report = report(orderId, side, ExecType.ORDER_STATUS, known ? OrdStatus.FILLED : OrdStatus.REJECTED);
            if (!known) {
                report.set(new OrdRejReason(OrdRejReason.UNKNOWN_ORDER));
            }
            send(report, sessionId);
        } else if (MsgType.ORDER_SINGLE.equals(type)) {
            place(message, orderId, side, sessionId);
        }
    }

    private void place(Message order, String orderId, char side, SessionID sessionId) throws FieldNotFound {
        if (!orders.add(orderId)) {
            reject(orderId, side, OrdRejReason.DUPLICATE_ORDER, "duplicate order", sessionId);
            return;
        }
        var isin = order.getString(SecurityID.FIELD);
        var price = prices.get(isin);
        if (price == null) {
            reject(orderId, side, OrdRejReason.UNKNOWN_SYMBOL, "unknown instrument", sessionId);
            return;
        }
        var quantity = side == Side.BUY
                ? new BigDecimal(order.getString(CashOrderQty.FIELD)).divide(price, 6, RoundingMode.DOWN)
                : new BigDecimal(order.getString(OrderQty.FIELD));
        if (quantity.signum() == 0) {
            reject(orderId, side, OrdRejReason.ORDER_EXCEEDS_LIMIT, "amount buys less than 0.000001 units", sessionId);
            return;
        }
        send(report(orderId, side, ExecType.NEW, OrdStatus.NEW), sessionId);
        var fill = report(orderId, side, ExecType.TRADE, OrdStatus.FILLED);
        fill.setString(LastQty.FIELD, quantity.toPlainString());
        fill.setString(LastPx.FIELD, price.toPlainString());
        fill.setString(CumQty.FIELD, quantity.toPlainString());
        fill.setString(AvgPx.FIELD, price.toPlainString());
        send(fill, sessionId);
        log.info("Filled {} {} units of {} at {}", orderId, quantity, isin, price);
    }

    private void reject(String orderId, char side, int reason, String text, SessionID sessionId) {
        var report = report(orderId, side, ExecType.REJECTED, OrdStatus.REJECTED);
        report.set(new OrdRejReason(reason));
        report.set(new Text(text));
        send(report, sessionId);
        log.info("Rejected {}: {}", orderId, text);
    }

    private ExecutionReport report(String orderId, char side, char execType, char ordStatus) {
        var report = new ExecutionReport(new OrderID("SIM-" + orderId), new ExecID("SIM-" + ids.incrementAndGet()),
                new ExecType(execType), new OrdStatus(ordStatus), new Side(side), new LeavesQty(0), new CumQty(0),
                new AvgPx(0));
        report.set(new ClOrdID(orderId));
        report.set(new Symbol("N/A"));
        return report;
    }

    private static void send(Message message, SessionID sessionId) {
        try {
            Session.sendToTarget(message, sessionId);
        } catch (SessionNotFound e) {
            log.warn("No session to answer on: {}", e.getMessage());
        }
    }

    private static Map<String, BigDecimal> prices() throws IOException {
        var properties = new Properties();
        try (InputStream in = ExecutionSimulator.class.getResourceAsStream("/prices.properties")) {
            properties.load(in);
        }
        var prices = new TreeMap<String, BigDecimal>();
        properties.forEach((isin, price) -> prices.put((String) isin, new BigDecimal((String) price)));
        return prices;
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
