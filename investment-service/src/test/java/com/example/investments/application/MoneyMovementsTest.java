package com.example.investments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.investments.application.port.Broker;
import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.PortfolioEvents;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.PostingPublisher;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.application.port.TransferAnswers;
import com.example.investments.application.port.TransferRequests;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.Quantity;
import com.example.investments.domain.TransferAnswer;
import com.example.investments.domain.TransferAnswer.Kind;
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

/** Money between portfolios and current accounts (ADR 0026, 0028), at the ports. */
class MoneyMovementsTest {

    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");
    private static final String ISIN = "IE00B4L5Y983";

    private final Portfolios portfolios = mock(Portfolios.class);
    private final Orders orders = mock(Orders.class);
    private final Payouts payouts = mock(Payouts.class);
    private final TransferAnswers answers = mock(TransferAnswers.class);
    private final ProcessedMessages processed = mock(ProcessedMessages.class);
    private final PortfolioEvents events = mock(PortfolioEvents.class);
    private final Broker broker = mock(Broker.class);
    private final PostingPublisher postings = mock(PostingPublisher.class);
    private final TransferRequests transfers = mock(TransferRequests.class);
    private final InvestmentMetrics metrics = mock(InvestmentMetrics.class);
    private final MoneyMovements money = new MoneyMovements(portfolios, orders, payouts, answers, processed, events,
            broker, new Books(portfolios, postings), new PayoutDesk(payouts, orders, transfers, metrics), metrics);

    private final Portfolio portfolio = Portfolio.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            AT, BigDecimal.ZERO, 0);

    @BeforeEach
    void setUp() {
        when(processed.markProcessed(anyString())).thenReturn(true);
        when(portfolios.lock(portfolio.id())).thenReturn(Optional.of(portfolio));
        when(answers.find(any(), anyString(), any())).thenReturn(Optional.empty());
    }

    private Instruction instruction(String transferId, Kind kind, String amount) {
        return new Instruction("msg-" + kind + transferId, portfolio.id(), transferId, kind,
                amount == null ? null : Money.of(amount), AT);
    }

    private Order buyWaitingForMoney(String amount) {
        var order = Order.buy(new Order.Request("ord-1", portfolio.id(), ISIN, Order.Side.BUY, Money.of(amount), null,
                AT));
        when(orders.find(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    private TransferAnswer answered() {
        var answer = ArgumentCaptor.forClass(TransferAnswer.class);
        verify(events).answered(answer.capture());
        return answer.getValue();
    }

    @Test
    void apply_DepositOfTheMoneyABuyWaitsFor_PlacesTheBuyForTodayAndAnswersDeposited() {
        var order = buyWaitingForMoney("500.00");

        money.apply(instruction(Order.fundingTransferId(order.id()), Kind.DEPOSIT, "500.00"));

        assertThat(order.status()).isEqualTo(Order.Status.PLACED);
        assertThat(order.tradingDay()).contains(LocalDate.parse("2026-11-24"));
        verify(broker).place(order, AT);
        assertThat(portfolio.cash()).isEqualByComparingTo("500.00");
        assertThat(answered().kind()).isEqualTo(Kind.DEPOSIT);
    }

    @Test
    void apply_DepositNoOrderWaitsFor_PaysItStraightBack() {
        money.apply(instruction("someone-elses-transfer", Kind.DEPOSIT, "20.00"));

        var payout = ArgumentCaptor.forClass(Payout.class);
        verify(payouts).add(payout.capture());
        assertThat(payout.getValue().reason()).isEqualTo(Payout.Reason.UNEXPECTED_DEPOSIT);
        var request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(transfers).request(request.capture());
        assertThat(request.getValue().toAccountId()).isEqualTo(portfolio.referenceAccountId());
        verify(broker, never()).place(any(), any());
    }

    @Test
    void apply_FundingOfAnotherAmountThanOrdered_PaysItBackAndFailsTheOrder() {
        var order = buyWaitingForMoney("500.00");

        money.apply(instruction(Order.fundingTransferId(order.id()), Kind.DEPOSIT, "499.00"));

        assertThat(order.status()).isEqualTo(Order.Status.FUNDING_FAILED);
        assertThat(order.rejectionReason()).contains(Order.FUNDING_MISMATCH);
        verify(orders).update(order);
        verify(metrics).orderEnded(order);
        var payout = ArgumentCaptor.forClass(Payout.class);
        verify(payouts).add(payout.capture());
        assertThat(payout.getValue().reason()).isEqualTo(Payout.Reason.UNEXPECTED_DEPOSIT);
        assertThat(payout.getValue().amount()).isEqualTo(Money.of("499.00"));
        verify(broker, never()).place(any(), any());
    }

    @Test
    void apply_WithdrawalNoPayoutWaitsFor_RejectsItAsNothingOwed() {
        when(payouts.findByTransfer("inv-payout-x-1")).thenReturn(Optional.empty());

        money.apply(instruction("inv-payout-x-1", Kind.WITHDRAWAL, "10.00"));

        assertThat(answered().rejectionReason()).isEqualTo(TransferAnswer.NOTHING_OWED);
        verify(portfolios, never()).update(any(), any());
    }

    @Test
    void apply_InstructionAnsweredBeforeForAnotherAmount_ThrowsIllegalInstruction() {
        when(answers.find(portfolio.id(), "t-1", Kind.DEPOSIT))
                .thenReturn(Optional.of(new TransferAnswer(portfolio.id(), "t-1", Kind.DEPOSIT, Money.of("5.00"), null,
                        AT)));

        assertThatThrownBy(() -> money.apply(instruction("t-1", Kind.DEPOSIT, "6.00")))
                .isInstanceOf(IllegalInstructionException.class);
    }

    @Test
    void outcome_ProceedsRefusedByTheCurrentAccount_MakesThePayoutAndItsOrderUnpaid() {
        var sell = Order.sell(new Order.Request("ord-s", portfolio.id(), ISIN, Order.Side.SELL, null,
                Quantity.of("1"), AT), LocalDate.parse("2026-11-24"));
        sell.expired();
        when(orders.find(sell.id())).thenReturn(Optional.of(sell));
        var payout = Payout.forOrder(portfolio.id(), sell.id(), Payout.Reason.PROCEEDS, Money.of("90.00"), AT);
        when(payouts.findByTransfer(payout.transferId())).thenReturn(Optional.of(payout));
        when(payouts.find(payout.id())).thenReturn(Optional.of(payout));

        money.outcome(new TransferOutcome("m-1", payout.transferId(), false, "CREDIT_REJECTED", "account blocked",
                AT));

        assertThat(payout.status()).isEqualTo(Payout.Status.UNPAID);
        assertThat(payout.unpaidSince()).contains(AT);
        assertThat(sell.settlement()).isEqualTo(Order.Settlement.UNPAID);
        verify(metrics).payoutUnpaid(Payout.Reason.PROCEEDS);
    }

    @Test
    void accountUnblocked_UnpaidPayout_TriesItAgainUnderANewTransferId() {
        var payout = Payout.forOrder(portfolio.id(), "ord-s", Payout.Reason.PROCEEDS, Money.of("90.00"), AT);
        var firstAttempt = payout.transferId();
        payout.unpaid(AT);
        when(payouts.unpaidIdsTo(portfolio.referenceAccountId())).thenReturn(List.of(payout.id()));
        when(payouts.find(payout.id())).thenReturn(Optional.of(payout));
        when(orders.find("ord-s")).thenReturn(Optional.empty());

        money.accountUnblocked("m-2", portfolio.referenceAccountId(), AT.plusSeconds(60));

        var request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(transfers).request(request.capture());
        assertThat(request.getValue().transferId()).isNotEqualTo(firstAttempt).endsWith("-2");
        assertThat(payout.status()).isEqualTo(Payout.Status.REQUESTED);
        assertThat(payout.unpaidSince()).contains(AT);
    }

    @Test
    void outcome_EventOfATransferInvestmentsDidNotRequest_IsIgnored() {
        money.outcome(new TransferOutcome("m-3", "sav-123", true, "", "", AT));

        verify(processed, never()).markProcessed(anyString());
    }
}
