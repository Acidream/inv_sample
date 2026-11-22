package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.savings.domain.Transfer.Direction;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-03-02T10:00:00Z");
    private static final Money AMOUNT = Money.of("40.00");

    @Test
    void aCompletedWithdrawalCanBeReturnedOnce() {
        var transfer = Transfer.withdrawn("t1", ACCOUNT, AMOUNT, NOW);

        assertThat(transfer.markReturned(NOW)).isTrue();
        assertThat(transfer.status()).isEqualTo(Transfer.Status.RETURNED);
        assertThat(transfer.markReturned(NOW)).as("a re-sent return is a no-op").isFalse();
    }

    @Test
    void aRejectedWithdrawalNeverLeftSoItCannotComeBack() {
        var transfer = Transfer.rejected("t1", ACCOUNT, AMOUNT, NOW);

        assertThatThrownBy(() -> transfer.markReturned(NOW)).isInstanceOf(IllegalTransferException.class);
    }

    @Test
    void aDepositCannotBeReturnedToSavings() {
        var transfer = Transfer.deposited("t1", ACCOUNT, AMOUNT, NOW);

        assertThatThrownBy(() -> transfer.markReturned(NOW)).isInstanceOf(IllegalTransferException.class);
    }

    @Test
    void onlyTheSameMovementUnderTheSameIdIsAResend() {
        var transfer = Transfer.withdrawn("t1", ACCOUNT, AMOUNT, NOW);

        assertThat(transfer.describes(Direction.OUT, ACCOUNT, Money.of("40.00"))).isTrue();
        assertThat(transfer.describes(Direction.IN, ACCOUNT, AMOUNT)).isFalse();
        assertThat(transfer.describes(Direction.OUT, UUID.randomUUID(), AMOUNT)).isFalse();
        assertThat(transfer.describes(Direction.OUT, ACCOUNT, Money.of("40.01"))).isFalse();
    }

    @Test
    void aStoredTransferIsRebuiltWithTheSameState() {
        var returned = Transfer.withdrawn("t1", ACCOUNT, AMOUNT, NOW);
        returned.markReturned(NOW);
        var stored = new Transfer.State("t1", ACCOUNT, Direction.OUT, AMOUNT, Transfer.Status.RETURNED, NOW, NOW, 3L);

        assertThat(Transfer.restore(stored).state()).isEqualTo(stored);
        assertThat(Transfer.restore(stored).markReturned(NOW)).isFalse();
    }

    @Test
    void onlyAWithdrawalCanBeStoredAsReturned() {
        var returnedDeposit = new Transfer.State("t1", ACCOUNT, Direction.IN, AMOUNT, Transfer.Status.RETURNED, NOW,
                NOW, 3L);

        assertThatIllegalArgumentException().isThrownBy(() -> Transfer.restore(returnedDeposit));
    }
}
