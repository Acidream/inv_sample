package com.example.fixgateway.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.beans.factory.DisposableBean;
import quickfix.ApplicationAdapter;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.ThreadedSocketAcceptor;
import quickfix.field.AvgPx;
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
import quickfix.field.PossDupFlag;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;

/**
 * Plays the broker: a FIX 4.4 acceptor in the test JVM, scripted by the features. It records the
 * orders and status requests the gateway sends and sends the reports a scenario asks for.
 */
public class TestBroker extends ApplicationAdapter implements DisposableBean {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final SessionID session = new SessionID("FIX.4.4", "BROKER", "BANK");
    private final List<Message> received = new ArrayList<>();
    private final Set<String> forgotten = ConcurrentHashMap.newKeySet();
    private final Map<String, Character> sides = new ConcurrentHashMap<>();
    private final int port;
    private final ThreadedSocketAcceptor acceptor;
    private volatile boolean refuseLogons;

    TestBroker() throws ConfigError, IOException {
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var settings = new SessionSettings();
        settings.setString(session, "ConnectionType", "acceptor");
        settings.setString(session, "BeginString", "FIX.4.4");
        settings.setString(session, "SenderCompID", "BROKER");
        settings.setString(session, "TargetCompID", "BANK");
        settings.setLong(session, "SocketAcceptPort", port);
        settings.setString(session, "NonStopSession", "Y");
        settings.setString(session, "UseDataDictionary", "Y");
        settings.setString(session, "DataDictionary", "FIX44.xml");
        settings.setLong(session, "HeartBtInt", 30);
        acceptor = new ThreadedSocketAcceptor(this, new MemoryStoreFactory(), settings, new SLF4JLogFactory(settings),
                new DefaultMessageFactory());
        acceptor.start();
    }

    public int port() {
        return port;
    }

    // --- what the gateway sent --------------------------------------------------------------------

    @Override
    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound {
        var type = message.getHeader().getString(MsgType.FIELD);
        var orderId = message.getString(ClOrdID.FIELD);
        if (MsgType.ORDER_SINGLE.equals(type)) {
            sides.put(orderId, message.getChar(Side.FIELD));
        }
        synchronized (received) {
            received.add(message);
        }
        if (MsgType.ORDER_STATUS_REQUEST.equals(type)) {
            answerStatus(orderId);
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound, RejectLogon {
        if (refuseLogons && MsgType.LOGON.equals(message.getHeader().getString(MsgType.FIELD))) {
            throw new RejectLogon("closed for the test");
        }
    }

    /** Waits for the order's NewOrderSingle and returns its fields, by tag number. */
    public Map<String, String> awaitOrder(String orderId) {
        return fields(await(MsgType.ORDER_SINGLE, orderId));
    }

    public Map<String, String> awaitStatusRequest(String orderId) {
        return fields(await(MsgType.ORDER_STATUS_REQUEST, orderId));
    }

    /** How many NewOrderSingles the gateway sent for the order so far. */
    public long ordersReceived(String orderId) {
        synchronized (received) {
            return received.stream().filter(matching(MsgType.ORDER_SINGLE, orderId)).count();
        }
    }

    // --- what the broker does ---------------------------------------------------------------------

    public void accept(String orderId) {
        send(report(orderId, ExecType.NEW, OrdStatus.NEW));
    }

    public void fill(String orderId, String execId, String quantity, String price, boolean done, boolean possDup) {
        var report = report(orderId, ExecType.TRADE, done ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED);
        report.setString(ExecID.FIELD, execId);
        report.setString(LastQty.FIELD, quantity);
        report.setString(LastPx.FIELD, price);
        report.set(new TransactTime(LocalDateTime.of(2026, 11, 24, 9, 30)));
        if (possDup) {
            report.getHeader().setField(new PossDupFlag(true));
        }
        send(report);
    }

    public void reject(String orderId, String text, boolean duplicate) {
        var report = report(orderId, ExecType.REJECTED, OrdStatus.REJECTED);
        report.set(new Text(text));
        report.set(new OrdRejReason(duplicate ? OrdRejReason.DUPLICATE_ORDER : OrdRejReason.OTHER));
        send(report);
    }

    public void expire(String orderId) {
        send(report(orderId, ExecType.EXPIRED, OrdStatus.EXPIRED));
    }

    public void cancel(String orderId, String text) {
        var report = report(orderId, ExecType.CANCELED, OrdStatus.CANCELED);
        report.set(new Text(text));
        send(report);
    }

    /** From now on, a status request for this order is answered "unknown order". */
    public void forget(String orderId) {
        forgotten.add(orderId);
    }

    /** Drops the connection; the gateway reconnects on its own. */
    public void disconnect() throws IOException {
        Session.lookupSession(session).disconnect("test", false);
    }

    public void refuseLogons(boolean refuse) {
        this.refuseLogons = refuse;
    }

    public boolean isLoggedOn() {
        var found = Session.lookupSession(session);
        return found != null && found.isLoggedOn();
    }

    /** An order the broker never received, or was told to forget, is unknown to it. */
    private void answerStatus(String orderId) {
        if (forgotten.contains(orderId) || !sides.containsKey(orderId)) {
            var report = report(orderId, ExecType.ORDER_STATUS, OrdStatus.REJECTED);
            report.set(new OrdRejReason(OrdRejReason.UNKNOWN_ORDER));
            send(report);
        } else {
            send(report(orderId, ExecType.ORDER_STATUS, OrdStatus.NEW));
        }
    }

    private ExecutionReport report(String orderId, char execType, char ordStatus) {
        var report = new ExecutionReport(new OrderID("B-" + orderId), new ExecID("x-" + System.nanoTime()),
                new ExecType(execType), new OrdStatus(ordStatus), new Side(sides.getOrDefault(orderId, Side.BUY)),
                new LeavesQty(0), new CumQty(0), new AvgPx(0));
        report.set(new ClOrdID(orderId));
        report.set(new Symbol("N/A"));
        return report;
    }

    private void send(Message message) {
        try {
            Session.sendToTarget(message, session);
        } catch (SessionNotFound e) {
            throw new IllegalStateException(e);
        }
    }

    private Message await(String type, String orderId) {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Message> found;
            synchronized (received) {
                found = received.stream().filter(matching(type, orderId)).findFirst();
                found.ifPresent(received::remove);
            }
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for FIX " + type + " of order " + orderId);
    }

    private static Predicate<Message> matching(String type, String orderId) {
        return message -> {
            try {
                return type.equals(message.getHeader().getString(MsgType.FIELD))
                        && orderId.equals(message.getString(ClOrdID.FIELD));
            } catch (FieldNotFound e) {
                return false;
            }
        };
    }

    private static Map<String, String> fields(Message message) {
        var fields = new java.util.TreeMap<String, String>();
        var iterator = message.iterator();
        while (iterator.hasNext()) {
            var field = iterator.next();
            fields.put(String.valueOf(field.getTag()), field.getObject().toString());
        }
        return fields;
    }

    @Override
    public void destroy() {
        acceptor.stop(true);
    }
}
