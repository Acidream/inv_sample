package com.example.fixgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.fixgateway.application.port.Broker;
import com.example.fixgateway.domain.BrokerOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Sending and recovery take turns, so a status request never overtakes its order (ADR 0027). */
class DispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private final GatewayService gateway = mock(GatewayService.class);
    private final Broker broker = mock(Broker.class);
    private final Dispatcher dispatcher = new Dispatcher(gateway, broker);

    private static BrokerOrder order(String orderId, BrokerOrder.Status status) {
        var placement = new BrokerOrder.Placement(orderId, "IE00B4L5Y983", BrokerOrder.Side.BUY,
                new BigDecimal("100.00"), null, LocalDate.parse("2026-11-24"));
        return BrokerOrder.restore(placement, NOW, status);
    }

    @Test
    void recover_WhileAnOrderIsBeingSent_AsksAboutItOnlyAfterItWentOut() throws Exception {
        var sending = order("ord-1", BrokerOrder.Status.SENDING);
        when(broker.isLoggedOn()).thenReturn(true);
        when(gateway.waitingToBeSent(anyInt())).thenReturn(List.of("ord-1"));
        when(gateway.startSending("ord-1", NOW)).thenReturn(Optional.of(sending));
        // Nothing open when the first send asks; the order being sent is open when recovery asks.
        when(gateway.withBroker()).thenReturn(List.of()).thenReturn(List.of(sending));
        var handingOver = new CountDownLatch(1);
        var mayFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            handingOver.countDown();
            mayFinish.await(5, TimeUnit.SECONDS);
            return null;
        }).when(broker).place(any());

        var send = CompletableFuture.runAsync(() -> dispatcher.sendWaiting(NOW));
        assertThat(handingOver.await(5, TimeUnit.SECONDS)).isTrue();
        var recovery = CompletableFuture.runAsync(dispatcher::recover);
        Thread.sleep(200);
        assertThat(recovery).isNotDone();
        mayFinish.countDown();
        send.get(5, TimeUnit.SECONDS);
        recovery.get(5, TimeUnit.SECONDS);

        var calls = inOrder(broker, gateway);
        calls.verify(broker).place(sending);
        calls.verify(gateway).sent("ord-1");
        calls.verify(broker).requestStatus(sending);
    }

    @Test
    void sendWaiting_WhileRecoveryAsks_SendsNothingUntilEveryRequestIsOut() throws Exception {
        var open = order("ord-1", BrokerOrder.Status.SENT);
        when(gateway.withBroker()).thenReturn(List.of(open));
        when(broker.isLoggedOn()).thenReturn(true);
        when(gateway.waitingToBeSent(anyInt())).thenReturn(List.of("ord-2"));
        var waiting = order("ord-2", BrokerOrder.Status.SENDING);
        when(gateway.startSending("ord-2", NOW)).thenReturn(Optional.of(waiting));
        var asking = new CountDownLatch(1);
        var mayFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            asking.countDown();
            mayFinish.await(5, TimeUnit.SECONDS);
            return null;
        }).when(broker).requestStatus(open);

        var recovery = CompletableFuture.runAsync(dispatcher::recover);
        assertThat(asking.await(5, TimeUnit.SECONDS)).isTrue();
        var send = CompletableFuture.supplyAsync(() -> dispatcher.sendWaiting(NOW));
        Thread.sleep(200);
        assertThat(send).isNotDone();
        mayFinish.countDown();
        recovery.get(5, TimeUnit.SECONDS);

        assertThat(send.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        var calls = inOrder(broker);
        calls.verify(broker).requestStatus(open);
        calls.verify(broker).place(waiting);
    }

    @Test
    void sendWaiting_LoggedOnBeforeRecoveryRan_AsksAboutOpenOrdersBeforeSending() {
        var open = order("ord-1", BrokerOrder.Status.SENT);
        var waiting = order("ord-2", BrokerOrder.Status.SENDING);
        when(gateway.withBroker()).thenReturn(List.of(open));
        when(broker.isLoggedOn()).thenReturn(true);
        when(gateway.waitingToBeSent(anyInt())).thenReturn(List.of("ord-2"));
        when(gateway.startSending("ord-2", NOW)).thenReturn(Optional.of(waiting));
        dispatcher.sessionChanged();

        assertThat(dispatcher.sendWaiting(NOW)).isEqualTo(1);

        var calls = inOrder(broker);
        calls.verify(broker).requestStatus(open);
        calls.verify(broker).place(waiting);
    }

    @Test
    void sendWaiting_AfterARecoveryThatFailed_AsksAgainAndSendsOnlyOnceItSucceeds() {
        var open = order("ord-1", BrokerOrder.Status.SENT);
        var waiting = order("ord-2", BrokerOrder.Status.SENDING);
        when(gateway.withBroker()).thenReturn(List.of(open));
        when(broker.isLoggedOn()).thenReturn(true);
        when(gateway.waitingToBeSent(anyInt())).thenReturn(List.of("ord-2"));
        when(gateway.startSending("ord-2", NOW)).thenReturn(Optional.of(waiting));
        doThrow(new Broker.BrokerUnavailableException("session gone", null)).doNothing()
                .when(broker).requestStatus(open);
        dispatcher.sessionChanged();

        assertThatThrownBy(dispatcher::recover).isInstanceOf(Broker.BrokerUnavailableException.class);
        assertThat(dispatcher.sendWaiting(NOW)).isEqualTo(1);

        var calls = inOrder(broker);
        calls.verify(broker).requestStatus(open);
        calls.verify(broker).requestStatus(open);
        calls.verify(broker).place(waiting);
    }

    @Test
    void sendWaiting_RecoveryOfThisLogonFails_SendsNothing() {
        var open = order("ord-1", BrokerOrder.Status.SENT);
        when(gateway.withBroker()).thenReturn(List.of(open));
        when(broker.isLoggedOn()).thenReturn(true);
        doThrow(new Broker.BrokerUnavailableException("session gone", null)).when(broker).requestStatus(open);

        assertThat(dispatcher.sendWaiting(NOW)).isZero();

        verify(broker, never()).place(any());
    }
}
