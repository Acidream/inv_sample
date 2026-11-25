package com.example.investments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.investments.application.port.Holdings;
import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.PostingPublisher;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.application.port.TransferRequests;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Execution;
import com.example.investments.domain.Fill;
import com.example.investments.domain.Holding;
import com.example.investments.domain.IllegalExecutionException;
import com.example.investments.domain.Lot;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.Price;
import com.example.investments.domain.Quantity;
import com.example.investments.domain.TransferRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Broker reports applied to orders, holdings and the portfolio's money (ADR 0026), at the ports. */
class TradingDeskTest {

    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-11-24");
    private static final String ISIN = "IE00B4L5Y983";

    private final Portfolios portfolios = mock(Portfolios.class);
    private final Orders orders = mock(Orders.class);
    private final Holdings holdings = mock(Holdings.class);
    private final ProcessedMessages processed = mock(ProcessedMessages.class);
    private final PostingPublisher postings = mock(PostingPublisher.class);
    private final Payouts payouts = mock(Payouts.class);
    private final TransferRequests transfers = mock(TransferRequests.class);
    private final InvestmentMetrics metrics = mock(InvestmentMetrics.class);
    private final TradingDesk desk = new TradingDesk(portfolios, orders, holdings, processed,
            new Books(portfolios, postings), new PayoutDesk(payouts, orders, transfers, metrics), metrics);

    private final Portfolio portfolio = Portfolio.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            AT, new BigDecimal("500.00"), 1);
    private Holding holding;

    @BeforeEach
    void setUp() {
        when(processed.markProcessed(anyString())).thenReturn(true);
        when(portfolios.lock(portfolio.id())).thenReturn(Optional.of(portfolio));
        holding = Holding.empty(portfolio.id(), ISIN);
        when(holdings.get(portfolio.id(), ISIN)).thenReturn(holding);
    }

    private Order stored(Order order) {
        when(orders.find(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    private Order placedBuy(String amount) {
        var order = Order.buy(new Order.Request("ord-b", portfolio.id(), ISIN, Order.Side.BUY, Money.of(amount), null,
                AT));
        order.funded(DAY);
        return stored(order);
    }

    private static BrokerReport.Filled fill(String orderId, String execId, String quantity, String price,
            boolean done) {
        return new BrokerReport.Filled("msg-" + execId, orderId,
                new Execution(execId, Quantity.of(quantity), Price.of(price), AT), done, AT);
    }

    @Test
    void apply_BuyFilledInFullForLessThanItsMoney_AddsALotAndRefundsTheRest() {
        var order = placedBuy("500.00");

        desk.apply(fill(order.id(), "e1", "5.208333", "95.90", true));

        assertThat(order.status()).isEqualTo(Order.Status.FILLED);
        assertThat(holding.quantity()).isEqualTo(Quantity.of("5.208333"));
        assertThat(holding.costBasis()).isEqualByComparingTo("499.48");
        var refund = ArgumentCaptor.forClass(TransferRequest.class);
        verify(transfers).request(refund.capture());
        assertThat(refund.getValue().amount()).isEqualTo(Money.of("0.52"));
        assertThat(refund.getValue().fromAccountId()).isEqualTo(portfolio.id());
        assertThat(refund.getValue().toAccountId()).isEqualTo(portfolio.referenceAccountId());
        var payout = ArgumentCaptor.forClass(Payout.class);
        verify(payouts).add(payout.capture());
        assertThat(payout.getValue().reason()).isEqualTo(Payout.Reason.REFUND);
    }

    @Test
    void apply_FillWorthLessThanACent_StoresTheFillAndLotWithoutBookingMoney() {
        var order = placedBuy("100.00");

        desk.apply(fill(order.id(), "e1", "0.000001", "96", false));

        var stored = ArgumentCaptor.forClass(Fill.class);
        verify(orders).addFill(any(), stored.capture());
        assertThat(stored.getValue().amount()).isEqualByComparingTo("0");
        assertThat(holding.quantity()).isEqualTo(Quantity.of("0.000001"));
        verify(postings, never()).publish(any(), any());
        verify(portfolios, never()).update(any(), any());
    }

    @Test
    void apply_SameExecutionReportedAgain_ChangesNothing() {
        var order = placedBuy("100.00");
        var report = fill(order.id(), "e1", "1", "50", false);
        when(orders.findFill(order.id(), "e1"))
                .thenReturn(Optional.of(new Fill("e1", Quantity.of("1"), Price.of("50"), new BigDecimal("50.00"),
                        null, AT)));

        desk.apply(report);

        assertThat(order.status()).isEqualTo(Order.Status.PLACED);
        verify(orders, never()).addFill(any(), any());
        verify(metrics).duplicateMessage();
    }

    @Test
    void apply_ExecutionIdReusedWithOtherDetails_ThrowsIllegalExecution() {
        var order = placedBuy("100.00");
        when(orders.findFill(order.id(), "e1"))
                .thenReturn(Optional.of(new Fill("e1", Quantity.of("1"), Price.of("49"), new BigDecimal("49.00"),
                        null, AT)));

        assertThatThrownBy(() -> desk.apply(fill(order.id(), "e1", "1", "50", false)))
                .isInstanceOf(IllegalExecutionException.class);
    }

    @Test
    void apply_SellFilledInPart_BooksTheProceedsWithTheirGainAndPaysThemOutWhenItExpires() {
        holding.bought(Lot.bought(new Fill("b1", Quantity.of("4"), Price.of("100"), new BigDecimal("400.00"), null,
                AT), "ord-old"), Price.of("100"));
        holding.reserve(Quantity.of("3"));
        var order = stored(Order.sell(new Order.Request("ord-s", portfolio.id(), ISIN, Order.Side.SELL, null,
                Quantity.of("3"), AT), DAY));

        desk.apply(fill(order.id(), "s1", "2", "120", false));
        desk.apply(new BrokerReport.Expired("msg-x", order.id(), AT));

        var fill = ArgumentCaptor.forClass(Fill.class);
        verify(orders).addFill(any(), fill.capture());
        assertThat(fill.getValue().realizedGain()).isEqualByComparingTo("40.00");
        assertThat(holding.reserved()).isEqualTo(Quantity.ZERO);
        assertThat(holding.free()).isEqualTo(Quantity.of("2"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CashEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(portfolios).update(any(), entries.capture());
        assertThat(entries.getValue()).singleElement().extracting(CashEntry::type).isEqualTo(CashEntry.Type.SELL);
        var proceeds = ArgumentCaptor.forClass(TransferRequest.class);
        verify(transfers).request(proceeds.capture());
        assertThat(proceeds.getValue().amount()).isEqualTo(Money.of("240.00"));
    }

    @Test
    void apply_ReportOnUnknownOrder_ThrowsIllegalExecution() {
        when(orders.find("ord-nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> desk.apply(new BrokerReport.Expired("m", "ord-nope", AT)))
                .isInstanceOf(IllegalExecutionException.class);
    }
}
