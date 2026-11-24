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
    private final KnownAccount portfolio = new KnownAccount(UUID.randomUUID(), CUSTOMER, Ledger.INVESTMENTS);

    private Transfer transfer(KnownAccount from, KnownAccount to) {
        return Transfer.requested(new Transfer.Request("t1", CUSTOMER, from.accountId(), to.accountId(), AMOUNT,
                Instant.parse("2026-03-01T10:00:00Z")));
    }

    @Test
    void start_CurrentToSavings_DebitsCurrentAccountThenCreditsSavings() {
        var transfer = transfer(current, savings);

        assertThat(transfer.start(Optional.of(current), Optional.of(savings)))
                .isEqualTo(new Step.DebitCurrentAccount(current.accountId(), "t1", AMOUNT));
        assertThat(transfer.debited(Ledger.CURRENT_ACCOUNT, current.accountId()))
                .isEqualTo(new Step.CreditProductLedger(Ledger.SAVINGS, savings.accountId(), "t1", AMOUNT));
        assertThat(transfer.credited(Ledger.SAVINGS, savings.accountId())).isEqualTo(new Step.Completed("t1"));
        assertThat(transfer.status()).isEqualTo(Transfer.Status.COMPLETED);
        assertThat(transfer.route()).contains(Transfer.Route.CURRENT_TO_SAVINGS);
    }

    @Test
    void start_SavingsToCurrent_DebitsSavingsThenCreditsCurrentAccount() {
        var transfer = transfer(savings, current);

        assertThat(transfer.start(Optional.of(savings), Optional.of(current)))
                .isEqualTo(new Step.DebitProductLedger(Ledger.SAVINGS, savings.accountId(), "t1", AMOUNT));
        assertThat(transfer.debited(Ledger.SAVINGS, savings.accountId()))
                .isEqualTo(new Step.CreditCurrentAccount(current.accountId(), "t1", AMOUNT));
        assertThat(transfer.credited(Ledger.CURRENT_ACCOUNT, current.accountId())).isEqualTo(new Step.Completed("t1"));
    }

    @Test
    void creditRefused_SavingsToCurrent_ReturnsMoneyAndFails() {
        var transfer = transfer(savings, current);
        transfer.start(Optional.of(savings), Optional.of(current));
        transfer.debited(Ledger.SAVINGS, savings.accountId());

        assertThat(transfer.creditRefused(Ledger.CURRENT_ACCOUNT, current.accountId(), "account closed"))
                .isEqualTo(new Step.ReturnToProductLedger(Ledger.SAVINGS, savings.accountId(), "t1"));
        assertThat(transfer.status()).isEqualTo(Transfer.Status.RETURNING);
        assertThat(transfer.returned(Ledger.SAVINGS, savings.accountId()))
                .isEqualTo(new Step.Failed("t1", Transfer.FailureReason.CREDIT_REFUSED, "account closed"));
    }

    @Test
    void start_CurrentToPortfolio_DebitsCurrentAccountThenCreditsInvestments() {
        var transfer = transfer(current, portfolio);

        assertThat(transfer.start(Optional.of(current), Optional.of(portfolio)))
                .isEqualTo(new Step.DebitCurrentAccount(current.accountId(), "t1", AMOUNT));
        assertThat(transfer.debited(Ledger.CURRENT_ACCOUNT, current.accountId()))
                .isEqualTo(new Step.CreditProductLedger(Ledger.INVESTMENTS, portfolio.accountId(), "t1", AMOUNT));
        assertThat(transfer.credited(Ledger.INVESTMENTS, portfolio.accountId())).isEqualTo(new Step.Completed("t1"));
        assertThat(transfer.route()).contains(Transfer.Route.CURRENT_TO_INVESTMENTS);
    }

    @Test
    void creditRefused_PortfolioToCurrent_ReturnsMoneyToInvestments() {
        var transfer = transfer(portfolio, current);
        assertThat(transfer.start(Optional.of(portfolio), Optional.of(current)))
                .isEqualTo(new Step.DebitProductLedger(Ledger.INVESTMENTS, portfolio.accountId(), "t1", AMOUNT));
        transfer.debited(Ledger.INVESTMENTS, portfolio.accountId());

        assertThat(transfer.creditRefused(Ledger.CURRENT_ACCOUNT, current.accountId(), "account blocked"))
                .isEqualTo(new Step.ReturnToProductLedger(Ledger.INVESTMENTS, portfolio.accountId(), "t1"));
        assertThatThrownBy(() -> transfer.returned(Ledger.SAVINGS, portfolio.accountId()))
                .isInstanceOf(IllegalTransferStateException.class);
        assertThat(transfer.returned(Ledger.INVESTMENTS, portfolio.accountId()))
                .isEqualTo(new Step.Failed("t1", Transfer.FailureReason.CREDIT_REFUSED, "account blocked"));
    }

    @Test
    void start_SavingsToPortfolio_FailsAsInvalidRequest() {
        var transfer = transfer(savings, portfolio);

        assertThat(transfer.start(Optional.of(savings), Optional.of(portfolio)))
                .isEqualTo(new Step.Failed("t1", Transfer.FailureReason.INVALID_REQUEST,
                        "transfers from SAVINGS to INVESTMENTS are not supported"));
    }

    @Test
    void debitRejected_Debiting_FailsWithoutMovingMoney() {
        var transfer = transfer(current, savings);
        transfer.start(Optional.of(current), Optional.of(savings));

        assertThat(transfer.debitRejected(Ledger.CURRENT_ACCOUNT, current.accountId(), "overdraft limit exceeded"))
                .isEqualTo(new Step.Failed("t1", Transfer.FailureReason.DEBIT_REJECTED, "overdraft limit exceeded"));
    }

    @Test
    void start_AccountUnknown_Waits() {
        var transfer = transfer(current, savings);

        assertThat(transfer.start(Optional.of(current), Optional.empty())).isEqualTo(new Step.Wait());
        assertThat(transfer.status()).isEqualTo(Transfer.Status.AWAITING_ACCOUNTS);
        assertThat(transfer.start(Optional.of(current), Optional.of(savings)))
                .isInstanceOf(Step.DebitCurrentAccount.class);
    }

    @Test
    void start_AccountOfAnotherCustomer_FailsAsInvalidRequest() {
        var someoneElses = new KnownAccount(UUID.randomUUID(), UUID.randomUUID(), Ledger.SAVINGS);
        var transfer = transfer(current, someoneElses);

        var step = transfer.start(Optional.of(current), Optional.of(someoneElses));

        assertThat(step).isInstanceOf(Step.Failed.class);
        assertThat(transfer.failureReason()).contains(Transfer.FailureReason.INVALID_REQUEST);
        assertThat(transfer.failureDetail()).hasValueSatisfying(detail -> assertThat(detail).contains("belong"));
    }

    @Test
    void start_CurrentToCurrent_FailsAsInvalidRequest() {
        var otherCurrent = new KnownAccount(UUID.randomUUID(), CUSTOMER, Ledger.CURRENT_ACCOUNT);
        var transfer = transfer(current, otherCurrent);

        transfer.start(Optional.of(current), Optional.of(otherCurrent));

        assertThat(transfer.failureReason()).contains(Transfer.FailureReason.INVALID_REQUEST);
    }

    @Test
    void credited_BeforeDebit_ThrowsIllegalTransferState() {
        var transfer = transfer(current, savings);
        assertThatThrownBy(() -> transfer.credited(Ledger.SAVINGS, savings.accountId()))
                .isInstanceOf(IllegalTransferStateException.class);
    }

    @Test
    void debited_ByWrongLedger_ThrowsIllegalTransferState() {
        var transfer = transfer(current, savings);
        transfer.start(Optional.of(current), Optional.of(savings));
        assertThatThrownBy(() -> transfer.debited(Ledger.SAVINGS, current.accountId()))
                .isInstanceOf(IllegalTransferStateException.class);
    }

    @Test
    void creditRefused_BySavings_ThrowsIllegalTransferState() {
        var transfer = transfer(current, savings);
        transfer.start(Optional.of(current), Optional.of(savings));
        transfer.debited(Ledger.CURRENT_ACCOUNT, current.accountId());
        assertThatThrownBy(() -> transfer.creditRefused(Ledger.SAVINGS, savings.accountId(), "no"))
                .isInstanceOf(IllegalTransferStateException.class);
    }

    @Test
    void debited_AnotherAccount_ThrowsIllegalTransferState() {
        var transfer = transfer(current, savings);
        transfer.start(Optional.of(current), Optional.of(savings));
        var otherCurrent = UUID.randomUUID();

        assertThatThrownBy(() -> transfer.debited(Ledger.CURRENT_ACCOUNT, otherCurrent))
                .isInstanceOf(IllegalTransferStateException.class)
                .hasMessageContaining(otherCurrent.toString());
        assertThat(transfer.status()).isEqualTo(Transfer.Status.DEBITING);
    }

    @Test
    void credited_AnotherAccount_ThrowsIllegalTransferState() {
        var transfer = transfer(current, savings);
        transfer.start(Optional.of(current), Optional.of(savings));
        transfer.debited(Ledger.CURRENT_ACCOUNT, current.accountId());

        assertThatThrownBy(() -> transfer.credited(Ledger.SAVINGS, UUID.randomUUID()))
                .isInstanceOf(IllegalTransferStateException.class);
        assertThat(transfer.status()).isEqualTo(Transfer.Status.CREDITING);
    }
}
