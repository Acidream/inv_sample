package com.example.fixgateway.adapter.fix;

import com.example.fixgateway.application.port.Broker;
import com.example.fixgateway.config.GatewayProperties;
import com.example.fixgateway.domain.BrokerOrder;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.JdbcStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

/**
 * The one FIX session to the broker (ADR 0027). Only one instance may hold it: sequence numbers
 * belong to one connection. Each instance tries a Postgres advisory lock on a connection of its own;
 * the one that gets it starts the session and keeps it while it still holds the lock, checked on
 * that connection every {@code leader-poll}. If the check fails, the session stops, and another
 * instance can take over: the session's state (sequence numbers, sent messages) is in the shared
 * database, so it continues where it was.
 *
 * <p>Postgres frees the lock as soon as it sees the holder's connection end, which can be before the
 * holder's next check. So a new holder waits {@code takeover-delay}, longer than a check takes to
 * notice, before it logs on, and the two sessions never overlap.
 */
@Component
class FixSession implements Broker, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FixSession.class);
    /** A fixed key, the same in every instance, so they all compete for one lock. */
    private static final long LOCK_KEY = 0x46495853L;

    private final DataSource dataSource;
    private final FixApplication application;
    private final GatewayProperties.Fix config;
    private final String account;
    private final Clock clock;
    private final SessionID sessionId;

    private volatile boolean running;
    private volatile SocketInitiator initiator;
    private volatile Connection lockConnection;
    private Thread leader;

    FixSession(DataSource dataSource, FixApplication application, GatewayProperties properties, Clock clock) {
        this.dataSource = dataSource;
        this.application = application;
        this.config = properties.fix();
        this.account = config.account();
        this.clock = clock;
        this.sessionId = new SessionID("FIX.4.4", config.senderCompId(), config.targetCompId());
    }

    // --- the port ---------------------------------------------------------------------------------

    @Override
    public boolean isLoggedOn() {
        var session = Session.lookupSession(sessionId);
        return initiator != null && session != null && session.isLoggedOn();
    }

    @Override
    public void place(BrokerOrder order) {
        send(FixMessages.newOrder(order, account, clock.instant()), order);
    }

    @Override
    public void requestStatus(BrokerOrder order) {
        send(FixMessages.statusRequest(order), order);
    }

    private void send(quickfix.Message message, BrokerOrder order) {
        try {
            if (!Session.sendToTarget(message, sessionId)) {
                // Stored with its sequence number; the broker gets it when it asks for the gap.
                log.warn("Order {}: message stored but not sent now", order.orderId());
            }
        } catch (SessionNotFound e) {
            throw new BrokerUnavailableException("No FIX session " + sessionId, e);
        }
    }

    /** Whether this instance holds the session (gauge {@code fix.session.held}). */
    boolean isHeld() {
        return initiator != null;
    }

    // --- lifecycle --------------------------------------------------------------------------------

    @Override
    public void start() {
        running = true;
        leader = Thread.ofPlatform().daemon().name("fix-leader").start(this::lead);
    }

    @Override
    public void stop() {
        running = false;
        if (leader != null) {
            leader.interrupt();
        }
        release();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** After the Kafka listeners and the outbox: reports from the session have somewhere to go. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void lead() {
        while (running) {
            try {
                if (initiator == null) {
                    if (takeLock()) {
                        takeOver();
                    }
                } else if (!holdsLock()) {
                    log.error("No longer holds the FIX session lock; stopping the session");
                    release();
                }
            } catch (SQLException | ConfigError | RuntimeException e) {
                log.warn("FIX session leadership: {}", e.getMessage());
                release();
            }
            try {
                Thread.sleep(config.leaderPoll().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean takeLock() throws SQLException {
        var connection = dataSource.getConnection();
        connection.setAutoCommit(true);
        try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (var result = statement.executeQuery()) {
                if (result.next() && result.getBoolean(1)) {
                    lockConnection = connection;
                    log.info("This instance holds the FIX session");
                    return true;
                }
            }
        }
        connection.close();
        return false;
    }

    /** Gives a former holder time to notice it lost the lock, then starts the session if still the holder. */
    private void takeOver() throws SQLException, ConfigError {
        try {
            Thread.sleep(config.takeoverDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release();
            return;
        }
        if (running && holdsLock()) {
            startInitiator();
        } else {
            release();
        }
    }

    /** Asks Postgres, on the lock's own connection, whether this session still holds the lock. */
    private boolean holdsLock() {
        var connection = lockConnection;
        if (connection == null) {
            return false;
        }
        try (var statement = connection.prepareStatement("""
                select exists (select 1 from pg_locks where locktype = 'advisory' and granted
                    and pid = pg_backend_pid() and classid::bigint = ? and objid::bigint = ? and objsubid = 1)""")) {
            statement.setQueryTimeout((int) GatewayProperties.LOCK_CHECK_TIMEOUT.toSeconds());
            statement.setLong(1, LOCK_KEY >>> 32);
            statement.setLong(2, LOCK_KEY & 0xFFFFFFFFL);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException e) {
            log.warn("Checking the FIX session lock failed: {}", e.getMessage());
            return false;
        }
    }

    private void startInitiator() throws ConfigError {
        var settings = settings();
        var store = new JdbcStoreFactory(settings);
        store.setDataSource(dataSource);
        var started = new SocketInitiator(application, store, settings, new SLF4JLogFactory(settings),
                new DefaultMessageFactory());
        started.start();
        initiator = started;
    }

    private SessionSettings settings() {
        var settings = new SessionSettings();
        settings.setString(sessionId, "ConnectionType", "initiator");
        settings.setString(sessionId, "BeginString", "FIX.4.4");
        settings.setString(sessionId, "SenderCompID", config.senderCompId());
        settings.setString(sessionId, "TargetCompID", config.targetCompId());
        settings.setString(sessionId, "SocketConnectHost", config.host());
        settings.setLong(sessionId, "SocketConnectPort", config.port());
        settings.setLong(sessionId, "HeartBtInt", config.heartbeatSeconds());
        settings.setLong(sessionId, "ReconnectInterval", Math.max(1, config.reconnectInterval().toSeconds()));
        settings.setString(sessionId, "NonStopSession", "Y");
        settings.setString(sessionId, "UseDataDictionary", "Y");
        settings.setString(sessionId, "DataDictionary", "FIX44.xml");
        settings.setString(sessionId, "PersistMessages", "Y");
        settings.setString(sessionId, "ResetOnLogon", "N");
        settings.setString(sessionId, "SLF4JLogHeartbeats", "N");
        return settings;
    }

    private synchronized void release() {
        var stopping = initiator;
        initiator = null;
        if (stopping != null) {
            stopping.stop(true);
        }
        var connection = lockConnection;
        lockConnection = null;
        if (connection != null) {
            // A session-level advisory lock outlives close(): the pool keeps the physical connection.
            try (var unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                unlock.setLong(1, LOCK_KEY);
                unlock.execute();
            } catch (SQLException e) {
                log.debug("Releasing the FIX session lock: {}", e.getMessage());
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Closing the lock connection: {}", e.getMessage());
            }
        }
    }
}
