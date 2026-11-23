package com.example.transfers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transfers.domain.KnownAccount.Ledger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Money AMOUNT = Money.of("100.00");

    private final KnownAccount current = new KnownAccount(UUID.randomUUID(), CUSTOMER, Ledger.CURRENT_ACCOUNT);
    private final KnownAccount savings = new KnownAccount(UUID.randomUUID(), CUSTOMER, Ledger.SAVINGS);

    private Transfer transfer(KnownAccount from, KnownAccount to) {
        return Transfer.requested(new Transfer.Request("t1", CUSTOMER, from.accountId(), to.accountId(), AMOUNT,
                Instant.parse("2026-03-01T10:00:00Z")));
    }

    @Test
    void currentAccountToSavingsDebitsTheCurrentAccountThenCreditsSavings() {
        var transfer = transfer(current, savings);

        assertThat(transfer.start(Optional.of(current), Optional.of(savings)))
                .isEqualTo(new Step.DebitCurrentAccount(current.accountId(), "t1", AMOUNT));
        assertThat(transfer.debited(Ledger.CURRENT_ACCOUNT))
                .isEqualTo(new Step.CreditSavings(savings.accountId(), "t1", AMOUNT));
        assertThat(transfer.credited(Ledger.SAVINGS)).isEqualTo(new Step.Completed("t1"));
        assertThat(transfer.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(transfer.route()).contains(Transfer.Route.CURRENT_TO_SAVINGS);
    }

    @Test
    void savingsToCurrentAccountDebitsSavingsThenCreditsTheCurrentAccount() {
        var transfer = transfer(savings, current);

        assertThat(transfer.start(Optional.of(savings), Optional.of(current)))
                .isEqualTo(new Step.DebitSavings(savings.accountId(), "t1", AMOUNT));
        assertThat(transfer.debited(Ledger.SAVINGS))
                .isEqualTo(new Step.CreditCurrentAccount(current.accountId(), "t1", AMOUNT));
        assertThat(transfer.credited(Ledger.CURRENT_ACCOUNT)).isEqualTo(new Step.Completed("t1"));
    }

    @Test
    void aRefusedCreditIsCompensatedAndTheTransferFails() {
        var transfer = transfer(savings, current);
        transfer.start(Optional.of(savings), Optional.of(current));
        transfer.debited(Ledger.SAVINGS);

        assertThat(transfer.creditRefused(Ledger.CURRENT_ACCOUNT, "account closed"))
                .isEqualTo(new Step.ReturnToSavings(savings.accountId(), "t1"));
        assertThat(transfer.status()).isEqualTo(Transfer.Status.RETURNING);
        assertThat(transfer.returned(Ledger.SAVINGS))
                .isEqualTo(new Step.Failed("t1", Transfer.FailureReason.CREDIT_REFUSED, "account closed"));
    }

    @Test
    void aRejectedDebitFailsWithoutMovingMoney() {
        var transfer = transfer(current, savings);
        transfer.start(Optional.of(current), Optional.of(savings));

        assertThat(transfer.debitRejected(Ledger.CURRENT_ACCOUNT, "overdraft limit exceeded"))
                .isEqualTo(new Step.Failed("t1", Transfer.FailureReason.DEBIT_REJECTED, "overdraft limit exceeded"));
    }

    @Test
    void itWaitsUntilBothAccountsAreKnown() {
        var transfer = transfer(current, savings);

        assertThat(transfer.start(Optional.of(current), Optional.empty())).isEqualTo(new Step.Wait());
        assertThat(transfer.status()).isEqualTo(Transfer.Status.AWAITING_ACCOUNTS);
        assertThat(transfer.start(Optional.of(current), Optional.of(savings)))
                .isInstanceOf(Step.DebitCurrentAccount.class);
    }

    @Test
    void anAccountOfAnotherCustomerIsAnInvalidRequest() {
        var someoneElses = new KnownAccount(UUID.randomUUID(), UUID.randomUUID(), Ledger.SAVINGS);
        var transfer = transfer(current, someoneElses);

        var step = transfer.start(Optional.of(current), Optional.of(someoneElses));

        assertThat(step).isInstanceOf(Step.Failed.class);
        assertThat(transfer.failureReason()).contains(Transfer.FailureReason.INVALID_REQUEST);
        assertThat(transfer.failureDetail()).hasValueSatisfying(detail -> assertThat(detail).contains("belong"));
    }

    @Test
    void onlyTransfersBetweenCurrentAccountAndSavingsAreSupported() {
        var otherCurrent = new KnownAccount(UUID.randomUUID(), CUSTOMER, Ledger.CURRENT_ACCOUNT);
        var transfer = transfer(current, otherCurrent);

        transfer.start(Optional.of(current), Optional.of(otherCurrent));

        assertThat(transfer.failureReason()).contains(Transfer.FailureReason.INVALID_REQUEST);
    }

    @Test
    void answersThatDontFitTheStateOrComeFromTheWrongLedgerAreContractViolations() {
        var transfer = transfer(current, savings);
        assertThatThrownBy(() -> transfer.credited(Ledger.SAVINGS)).isInstanceOf(IllegalTransferStateException.class);

        transfer.start(Optional.of(current), Optional.of(savings));
        assertThatThrownBy(() -> transfer.debited(Ledger.SAVINGS)).isInstanceOf(IllegalTransferStateException.class);

        transfer.debited(Ledger.CURRENT_ACCOUNT);
        assertThatThrownBy(() -> transfer.creditRefused(Ledger.SAVINGS, "no"))
                .isInstanceOf(IllegalTransferStateException.class);
    }
}
