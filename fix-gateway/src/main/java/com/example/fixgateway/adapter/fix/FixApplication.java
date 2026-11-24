package com.example.fixgateway.adapter.fix;

import com.example.fixgateway.application.Dispatcher;
import com.example.fixgateway.application.GatewayService;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import quickfix.ApplicationAdapter;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.fix44.ExecutionReport;

/**
 * QuickFIX/J's callbacks. An {@code ExecutionReport} is applied in its own transaction before
 * QuickFIX/J counts it as received; if that keeps failing, the session is dropped, so the broker
 * resends it after the next logon rather than it being lost.
 */
@Component
class FixApplication extends ApplicationAdapter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FixApplication.class);
    private static final int ATTEMPTS = 3;

    private final GatewayService gateway;
    private final ObjectProvider<Dispatcher> dispatcher;
    private final Clock clock;
    private final ExecutorService afterLogon = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("fix-after-logon").factory());

    FixApplication(GatewayService gateway, ObjectProvider<Dispatcher> dispatcher, Clock clock) {
        this.gateway = gateway;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /** Not on QuickFIX/J's thread: the status requests go out through the session it is running. */
    @Override
    public void onLogon(SessionID sessionId) {
        log.info("FIX session {} logged on", sessionId);
        afterLogon.submit(() -> {
            try {
                dispatcher.getObject().recover();
            } catch (RuntimeException e) {
                log.error("Recovery after logon failed", e);
            }
        });
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.warn("FIX session {} logged out", sessionId);
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound, UnsupportedMessageType {
        if (!(message instanceof ExecutionReport report)) {
            log.debug("Ignoring {} from the broker", message.getHeader().getString(35));
            return;
        }
        if (!report.isSetClOrdID()) {
            log.warn("Ignoring an execution report without ClOrdID: {}", message);
            return;
        }
        var event = FixMessages.event(report);
        if (event.isEmpty()) {
            log.debug("Ignoring execution report {}", message);
            return;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                gateway.report(event.get(), clock.instant());
                return;
            } catch (RuntimeException e) {
                if (attempt == ATTEMPTS) {
                    log.error("Could not apply {}; dropping the session so the broker resends it", event.get(), e);
                    var session = Session.lookupSession(sessionId);
                    if (session != null) {
                        try {
                            session.disconnect("could not apply an execution report", true);
                        } catch (IOException disconnect) {
                            e.addSuppressed(disconnect);
                        }
                    }
                    throw e;
                }
                log.warn("Applying {} failed (attempt {}): {}", event.get(), attempt, e.getMessage());
            }
        }
    }

    @Override
    public void destroy() {
        afterLogon.shutdownNow();
    }
}
