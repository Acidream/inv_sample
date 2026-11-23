package com.example.savings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.savings.application.PaymentInstruction.ReturnTransfer;
import com.example.savings.application.PaymentInstruction.TransferIn;
import com.example.savings.application.PaymentInstruction.TransferOut;
import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.ProcessedEvents;
import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.application.port.Transfers;
import com.example.savings.domain.Account;
import com.example.savings.domain.AccountEvent.RejectionReason;
import com.example.savings.domain.AccountEvent.WithdrawalRejected;
import com.example.savings.domain.AccountEvent.WithdrawalReturned;
import com.example.savings.domain.IllegalTransferException;
import com.example.savings.domain.Money;
import com.example.savings.domain.PayoutFrequency;
import com.example.savings.domain.SavingsProduct;
import com.example.savings.domain.Transfer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Use-case rules with the ports mocked; the full wiring is covered by the acceptance tests. */
@ExtendWith(MockitoExtension.class)
class PaymentInstructionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-02T10:00:00Z");

    @Mock Accounts accounts;
    @Mock Transfers transfers;
    @Mock Bookkeeping bookkeeping;
    @Mock ProcessedEvents processedEvents;
    @Mock AccountEventPublisher events;
    @Mock SavingsMetrics metrics;

    private PaymentInstructionHandler handler;
    private Account account;

    @BeforeEach
    void setUp() {
        handler = new PaymentInstructionHandler(accounts, transfers, bookkeeping, processedEvents, events, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
        account = Account.open(UUID.randomUUID(), UUID.randomUUID(),
                new SavingsProduct("TEST", 1, "Test", BigDecimal.ZERO, PayoutFrequency.MONTHLY), NOW, ZoneOffset.UTC);
    }

    @Test
    void duplicateMessageChangesNothing() {
        when(processedEvents.markProcessed("e1")).thenReturn(false);

        handler.handle(new TransferIn("e1", account.id(), "t1", Money.of("10.00")));

        verifyNoInteractions(accounts, transfers, bookkeeping, events);
        verify(metrics).duplicateMessage();
    }

    @Test
    void insufficientFundsIsABusinessOutcomeNotAnError() {
        when(processedEvents.markProcessed("e1")).thenReturn(true);
        when(accounts.get(account.id())).thenReturn(account);
        when(transfers.find("t1")).thenReturn(Optional.empty());

        handler.handle(new TransferOut("e1", account.id(), "t1", Money.of("0.01")));

        verify(events).publish(new WithdrawalRejected(account.id(), "t1", Money.of("0.01"),
                RejectionReason.INSUFFICIENT_FUNDS));
        verify(bookkeeping, never()).book(any());
        assertThat(account.balance()).isEqualTo(Money.ZERO);
    }

    @Test
    void transferResentUnderNewMessageIdIsAppliedOnce() {
        when(processedEvents.markProcessed("e2")).thenReturn(true);
        when(accounts.get(account.id())).thenReturn(account);
        when(transfers.find("t1")).thenReturn(Optional.of(Transfer.deposited("t1", account.id(), Money.of("10.00"),
                NOW)));

        handler.handle(new TransferIn("e2", account.id(), "t1", Money.of("10.00")));

        assertThat(account.balance()).isEqualTo(Money.ZERO);
        verifyNoInteractions(bookkeeping, events);
        verify(metrics).duplicateMessage();
    }

    @Test
    void transferIdReusedForADifferentMovementIsAContractViolation() {
        when(processedEvents.markProcessed("e2")).thenReturn(true);
        when(accounts.get(account.id())).thenReturn(account);
        when(transfers.find("t1")).thenReturn(Optional.of(Transfer.deposited("t1", account.id(), Money.of("10.00"),
                NOW)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(new TransferOut("e2", account.id(), "t1", Money.of("10.00"))))
                .withMessageContaining("t1");
        verifyNoInteractions(bookkeeping, events);
    }

    @Test
    void returnedWithdrawalIsCreditedBackOnce() {
        account.deposit(Money.of("100.00"), "t0", NOW);
        account.withdraw(Money.of("40.00"), "t1", NOW);
        var transfer = Transfer.withdrawn("t1", account.id(), Money.of("40.00"), NOW);
        when(processedEvents.markProcessed("e3")).thenReturn(true);
        when(processedEvents.markProcessed("e4")).thenReturn(true);
        when(transfers.find("t1")).thenReturn(Optional.of(transfer));
        when(accounts.get(account.id())).thenReturn(account);

        handler.handle(new ReturnTransfer("e3", account.id(), "t1"));
        handler.handle(new ReturnTransfer("e4", account.id(), "t1"));

        assertThat(account.balance()).isEqualTo(Money.of("100.00"));
        // Nothing is saved implicitly: both changed aggregates are handed back to their ports, once.
        verify(transfers).update(transfer);
        verify(accounts).update(account);
        verify(events).publish(new WithdrawalReturned(account.id(), "t1", Money.of("40.00"), Money.of("100.00")));
        verify(metrics).duplicateMessage();
    }

    @Test
    void returningARejectedWithdrawalIsAContractViolation() {
        when(processedEvents.markProcessed("e3")).thenReturn(true);
        when(transfers.find("t1")).thenReturn(Optional.of(Transfer.rejected("t1", account.id(), Money.of("40.00"),
                NOW)));

        assertThatThrownBy(() -> handler.handle(new ReturnTransfer("e3", account.id(), "t1")))
                .isInstanceOf(IllegalTransferException.class);
        verifyNoInteractions(bookkeeping, events);
    }

    @Test
    void returnForAnotherAccountIsRejected() {
        when(processedEvents.markProcessed("e3")).thenReturn(true);
        when(transfers.find("t1")).thenReturn(Optional.of(Transfer.withdrawn("t1", UUID.randomUUID(),
                Money.of("40.00"), NOW)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(new ReturnTransfer("e3", account.id(), "t1")));
    }
}
