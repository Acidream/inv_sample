package com.example.investments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's portfolio: holdings, and a money ledger that is empty at rest (ADR 0026). Refunds and
 * proceeds always go to the reference account, the customer's current account, which never changes.
 *
 * <p>Every change to a portfolio, its orders, holdings or payouts happens with the portfolio locked:
 * its inputs arrive on five topics, consumed concurrently.
 */
public final class Portfolio {

    private final UUID id;
    private final UUID customerId;
    private final UUID referenceAccountId;
    private final Instant openedAt;
    private BigDecimal cash;
    private long lastEntryNo;

    private Portfolio(UUID id, UUID customerId, UUID referenceAccountId, Instant openedAt, BigDecimal cash,
            long lastEntryNo) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.referenceAccountId = Objects.requireNonNull(referenceAccountId, "referenceAccountId");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.cash = Objects.requireNonNull(cash, "cash").setScale(Money.SCALE);
        this.lastEntryNo = lastEntryNo;
        if (this.cash.signum() < 0) {
            throw new IllegalArgumentException("A portfolio's money can't be negative: " + cash);
        }
    }

    public static Portfolio open(UUID id, UUID customerId, UUID referenceAccountId, Instant openedAt) {
        return new Portfolio(id, customerId, referenceAccountId, openedAt, BigDecimal.ZERO, 0);
    }

    public static Portfolio restore(UUID id, UUID customerId, UUID referenceAccountId, Instant openedAt,
            BigDecimal cash, long lastEntryNo) {
        return new Portfolio(id, customerId, referenceAccountId, openedAt, cash, lastEntryNo);
    }

    /** True if opening this portfolio again with these details would change nothing. */
    public boolean openedAs(UUID otherCustomerId, UUID otherReferenceAccountId) {
        return customerId.equals(otherCustomerId) && referenceAccountId.equals(otherReferenceAccountId);
    }

    /**
     * Books money on the portfolio's ledger, in or out as its type says.
     *
     * @throws IllegalStateException if money leaves that the portfolio doesn't hold: every debit pays
     *                               for money the portfolio received for it, so this is a bug
     */
    public CashEntry book(CashEntry.Booking booking) {
        var amount = booking.amount().amount();
        if (!booking.type().isCredit() && cash.compareTo(amount) < 0) {
            throw new IllegalStateException("Portfolio " + id + " holds " + cash + ", can't pay " + booking.amount()
                    + " for " + booking.reference());
        }
        var signed = booking.type().isCredit() ? amount : amount.negate();
        cash = cash.add(signed);
        lastEntryNo++;
        return new CashEntry(id, lastEntryNo, booking.type(), signed, cash, booking.reference(), booking.orderId(),
                booking.trade(), booking.at());
    }

    /** The transfer that brings a buy's money from the reference account. */
    public TransferRequest funding(Order order) {
        if (!order.portfolioId().equals(id) || order.side() != Order.Side.BUY) {
            throw new IllegalArgumentException("Order " + order.id() + " is not a buy of portfolio " + id);
        }
        return new TransferRequest(Order.fundingTransferId(order.id()), customerId, referenceAccountId, id,
                order.request().amount(), order.request().acceptedAt());
    }

    /** The transfer of a payout's current attempt to the reference account. */
    public TransferRequest payout(Payout payout, Instant at) {
        if (!payout.portfolioId().equals(id)) {
            throw new IllegalArgumentException("Payout " + payout.id() + " is not owed by portfolio " + id);
        }
        return new TransferRequest(payout.transferId(), customerId, id, referenceAccountId, payout.amount(), at);
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID referenceAccountId() {
        return referenceAccountId;
    }

    public Instant openedAt() {
        return openedAt;
    }

    /** Money on the portfolio: for orders in flight, payouts in flight, and unpaid payouts. */
    public BigDecimal cash() {
        return cash;
    }

    public long lastEntryNo() {
        return lastEntryNo;
    }
}
