package com.example.savings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.savings.application.PaymentInstruction.ConfirmWithdrawal;
import com.example.savings.application.PaymentInstruction.Deposit;
import com.example.savings.application.PaymentInstruction.RequestWithdrawal;
import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.Holds;
import com.example.savings.application.port.Ledger;
import com.example.savings.application.port.ProcessedEvents;
import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.domain.Account;
import com.example.savings.domain.AccountEvent;
import com.example.savings.domain.AccountEvent.MoneyWithdrawn;
import com.example.savings.domain.AccountEvent.RejectionReason;
import com.example.savings.domain.AccountEvent.WithdrawalRejected;
import com.example.savings.domain.Hold;
import com.example.savings.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Use-case rules with the ports mocked; the full wiring is covered by the acceptance tests. */
@ExtendWith(MockitoExtension.class)
class PaymentInstructionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-02T10:00:00Z");

    @Mock Accounts accounts;
    @Mock Holds holds;
    @Mock Ledger ledger;
    @Mock ProcessedEvents processedEvents;
    @Mock AccountEventPublisher events;
    @Mock SavingsMetrics metrics;

    private PaymentInstructionHandler handler;
    private Account account;

    @BeforeEach
    void setUp() {
        var policy = new SavingsPolicy(ZoneId.of("Europe/Berlin"), Duration.ofHours(72));
        handler = new PaymentInstructionHandler(accounts, holds, ledger, processedEvents, events, metrics, policy,
                Clock.fixed(NOW, ZoneOffset.UTC));
        account = Account.open(UUID.randomUUID(), BigDecimal.ZERO, NOW, ZoneOffset.UTC);
    }

    @Test
    void duplicateMessageChangesNothing() {
        when(processedEvents.markProcessed("e1")).thenReturn(false);

        handler.handle(new Deposit("e1", account.id(), "p1", Money.of("10.00")));

        verifyNoInteractions(accounts, ledger, events);
        verify(metrics).duplicateMessage();
    }

    @Test
    void insufficientFundsIsABusinessOutcomeNotAnError() {
        when(processedEvents.markProcessed("e1")).thenReturn(true);
        when(holds.findByRequestId("wd-1")).thenReturn(Optional.empty());
        when(accounts.get(account.id())).thenReturn(account);

        handler.handle(new RequestWithdrawal("e1", account.id(), "wd-1", Money.of("0.01")));

        verify(events).publish(new WithdrawalRejected(account.id(), "wd-1", Money.of("0.01"),
                RejectionReason.INSUFFICIENT_FUNDS));
        verify(holds, never()).add(any());
    }

    @Test
    void lateConfirmationThatOverdrawsIsBookedAndFlagged() {
        account.deposit(Money.of("50.00"), "p1", NOW);
        var hold = Hold.reserve(account.id(), "wd-1", Money.of("80.00"), NOW.minus(Duration.ofDays(4)),
                Duration.ofHours(72));
        hold.expire(NOW);
        when(processedEvents.markProcessed("e2")).thenReturn(true);
        when(holds.findByRequestId("wd-1")).thenReturn(Optional.of(hold));
        when(accounts.get(account.id())).thenReturn(account);

        handler.handle(new ConfirmWithdrawal("e2", account.id(), "wd-1"));

        assertThat(account.balance()).isEqualTo(Money.of("-30.00"));
        var published = ArgumentCaptor.forClass(AccountEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue()).isEqualTo(
                new MoneyWithdrawn(account.id(), "wd-1", Money.of("80.00"), Money.of("-30.00")));
        verify(metrics).negativeBalance();
    }

    @Test
    void confirmationForAnotherAccountIsRejected() {
        var hold = Hold.reserve(UUID.randomUUID(), "wd-1", Money.of("1.00"), NOW, Duration.ofHours(72));
        when(processedEvents.markProcessed("e3")).thenReturn(true);
        when(holds.findByRequestId("wd-1")).thenReturn(Optional.of(hold));

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(new ConfirmWithdrawal("e3", account.id(), "wd-1")));
    }
}
