package com.example.savings.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.savings.domain.Money;
import com.example.savings.domain.PayoutFrequency;
import com.example.savings.domain.SavingsProduct;
import com.example.savings.domain.Transfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final SavingsProduct V2 =
            new SavingsProduct("INSTANT_ACCESS", 2, "Instant access", new BigDecimal("0.025"), PayoutFrequency.MONTHLY);

    private final Order.Request request = new Order.Request(UUID.randomUUID(), UUID.randomUUID(), "INSTANT_ACCESS", 2,
            Money.of("250.00"), UUID.randomUUID(), Instant.parse("2026-03-01T10:00:00Z"));

    @Test
    void anOrderForTheCurrentVersionOpensAnAccountAndAsksForTheMoney() {
        var order = Order.place(request, Optional.of(V2));

        assertThat(order.status()).isEqualTo(Order.Status.FUNDING);
        assertThat(order.accountId()).contains(Order.accountIdFor(request.orderId()));
        assertThat(order.transferId()).contains("savings-order-" + request.orderId());
    }

    @Test
    void theSameOrderAlwaysGetsTheSameAccountAndTransfer() {
        assertThat(Order.accountIdFor(request.orderId())).isEqualTo(Order.accountIdFor(request.orderId()));
        assertThat(Order.place(request, Optional.of(V2)).state().accountId())
                .isEqualTo(Order.place(request, Optional.of(V2)).state().accountId());
    }

    @Test
    void anOrderForAProductNotOnSaleIsRejected() {
        var order = Order.place(request, Optional.empty());

        assertThat(order.status()).isEqualTo(Order.Status.REJECTED);
        assertThat(order.rejectionReason()).contains(Order.RejectionReason.PRODUCT_NOT_ON_SALE);
        assertThat(order.accountId()).isEmpty();
    }

    @Test
    void termsThatHaveBeenReplacedAreNotSoldAnyMore() {
        var v3 = new SavingsProduct("INSTANT_ACCESS", 3, "Instant access", new BigDecimal("0.02"),
                PayoutFrequency.MONTHLY);

        var order = Order.place(request, Optional.of(v3));

        assertThat(order.rejectionReason()).contains(Order.RejectionReason.PRODUCT_VERSION_NOT_CURRENT);
    }

    private static Optional<Transfer> deposit(Order order, Money amount) {
        return Optional.of(Transfer.deposited(order.transferId().orElseThrow(), order.accountId().orElseThrow(),
                amount, Instant.parse("2026-03-01T10:00:05Z")));
    }

    @Test
    void theFundingTransferDecidesTheOutcome() {
        var funded = Order.place(request, Optional.of(V2));
        funded.funded(deposit(funded, request.amount()));
        funded.funded(deposit(funded, request.amount()));
        assertThat(funded.status()).isEqualTo(Order.Status.FUNDED);

        var failed = Order.place(request, Optional.of(V2));
        failed.fundingFailed("DEBIT_REJECTED: overdraft limit exceeded", Optional.empty());
        assertThat(failed.status()).isEqualTo(Order.Status.FUNDING_FAILED);
        assertThat(failed.failureReason()).contains("DEBIT_REJECTED: overdraft limit exceeded");
    }

    @Test
    void aCompletedTransferFundsTheOrderOnlyIfSavingsBookedTheOrdersAmountToItsAccount() {
        var order = Order.place(request, Optional.of(V2));
        var toAnotherAccount = Optional.of(Transfer.deposited(order.transferId().orElseThrow(), UUID.randomUUID(),
                request.amount(), Instant.parse("2026-03-01T10:00:05Z")));

        assertThatThrownBy(() -> order.funded(Optional.empty())).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> order.funded(deposit(order, Money.of("1.00"))))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> order.funded(toAnotherAccount)).isInstanceOf(IllegalOrderTransitionException.class);
        assertThat(order.status()).isEqualTo(Order.Status.FUNDING);
    }

    @Test
    void aFailedTransferUnderWhichSavingsBookedMoneyIsAContractViolation() {
        var order = Order.place(request, Optional.of(V2));

        assertThatThrownBy(() -> order.fundingFailed("DEBIT_REJECTED", deposit(order, request.amount())))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThat(order.status()).isEqualTo(Order.Status.FUNDING);
    }

    @Test
    void contradictingOutcomesAreAContractViolation() {
        var order = Order.place(request, Optional.of(V2));
        order.fundingFailed("DEBIT_REJECTED", Optional.empty());

        assertThatThrownBy(() -> order.funded(deposit(order, request.amount())))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> Order.place(request, Optional.empty()).funded(Optional.empty()))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void aRetryIsTheSameOrderWhateverTimeItWasSent() {
        var retry = new Order.Request(request.orderId(), request.customerId(), request.productCode(),
                request.productVersion(), request.amount(), request.fromAccountId(), request.placedAt().plusSeconds(5));
        var different = new Order.Request(request.orderId(), request.customerId(), request.productCode(),
                request.productVersion(), Money.of("251.00"), request.fromAccountId(), request.placedAt());

        assertThat(request.sameOrderAs(retry)).isTrue();
        assertThat(request.sameOrderAs(different)).isFalse();
    }
}
