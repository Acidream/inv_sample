package com.example.fixgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.fixgateway.application.port.BrokerOrders;
import com.example.fixgateway.application.port.Executions;
import com.example.fixgateway.application.port.ProcessedMessages;
import com.example.fixgateway.application.port.Reports;
import com.example.fixgateway.domain.BrokerOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Recovery after a logon, where the state before a crash decides what an answer means (ADR 0027). */
class GatewayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private final BrokerOrders orders = mock(BrokerOrders.class);
    private final Reports reports = mock(Reports.class);
    private final GatewayService gateway = new GatewayService(orders, mock(Executions.class),
            mock(ProcessedMessages.class), reports);

    private BrokerOrder order(BrokerOrder.Status status) {
        var placement = new BrokerOrder.Placement("ord-1", "IE00B4L5Y983", BrokerOrder.Side.BUY,
                new BigDecimal("100.00"), null, LocalDate.parse("2026-11-24"));
        var order = BrokerOrder.restore(placement, NOW, status);
        when(orders.lock("ord-1")).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    void report_UnknownAfterInterruptedSending_SendsItAgainWithoutTellingInvestments() {
        var order = order(BrokerOrder.Status.SENDING);

        gateway.report(new BrokerReport.Status("ord-1", false), NOW);

        assertThat(order.status()).isEqualTo(BrokerOrder.Status.RECEIVED);
        verify(reports, never()).rejected(anyString(), anyString(), any());
    }

    @Test
    void report_UnknownAfterTheBrokerAcceptedIt_RejectsTheOrder() {
        var order = order(BrokerOrder.Status.ACCEPTED);

        gateway.report(new BrokerReport.Status("ord-1", false), NOW);

        assertThat(order.isDone()).isTrue();
        verify(reports).rejected(eq("ord-1"), eq(GatewayService.UNKNOWN_AT_BROKER), any());
    }

    @Test
    void report_KnownAfterInterruptedSending_CountsAsArrived() {
        var order = order(BrokerOrder.Status.SENDING);

        gateway.report(new BrokerReport.Status("ord-1", true), NOW);

        assertThat(order.status()).isEqualTo(BrokerOrder.Status.ACCEPTED);
    }

    @Test
    void report_DuplicateRejectedWhileWithBroker_LeavesTheOrderOpen() {
        var order = order(BrokerOrder.Status.SENT);

        gateway.report(new BrokerReport.Rejected("ord-1", "duplicate", true), NOW);

        assertThat(order.isDone()).isFalse();
        verify(reports, never()).rejected(anyString(), anyString(), any());
    }

    @Test
    void report_SecondFinalReport_IsIgnored() {
        order(BrokerOrder.Status.ACCEPTED);

        gateway.report(new BrokerReport.Expired("ord-1"), NOW);
        gateway.report(new BrokerReport.Rejected("ord-1", "late", false), NOW);

        verify(reports).expired(eq("ord-1"), any());
        verify(reports, never()).rejected(anyString(), anyString(), any());
    }

    @Test
    void startSending_TradingDayOver_ExpiresWithoutSending() {
        order(BrokerOrder.Status.RECEIVED);

        var toSend = gateway.startSending("ord-1", Instant.parse("2026-11-24T23:00:00Z"));

        assertThat(toSend).isEmpty();
        verify(reports).expired(eq("ord-1"), any());
    }
}
