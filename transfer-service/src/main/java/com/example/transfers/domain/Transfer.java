package com.example.transfers.domain;

import com.example.transfers.domain.KnownAccount.Ledger;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A transfer between two accounts of the same customer, as a saga across their ledgers (ADR 0015,
 * 0018). The source is always debited first; if the target then refuses the credit, the debit is
 * compensated. Every method returns the {@link Step} to carry out next.
 *
 * <pre>
 * AWAITING_ACCOUNTS --&gt; DEBITING --&gt; CREDITING --&gt; COMPLETED
 *        |               |             |
 *        v               v             v
 *      FAILED          FAILED       RETURNING --&gt; FAILED      (only savings to current account)
 * </pre>
 *
 * <p>A credit to savings is never refused: savings accounts are opened before money is sent to them
 * (ADR 0020), so only the current account can refuse a credit.
 */
public final class Transfer {

    public enum Route { CURRENT_TO_SAVINGS, SAVINGS_TO_CURRENT }

    public enum Status { AWAITING_ACCOUNTS, DEBITING, CREDITING, RETURNING, COMPLETED, FAILED }

    public enum FailureReason {
        /** Unknown account, not the customer's, or a route that isn't supported. Nothing was debited. */
        INVALID_REQUEST,
        /** The source refused the debit. Nothing was debited. */
        DEBIT_REJECTED,
        /** The target refused the credit; the debit was credited back. */
        CREDIT_REFUSED
    }

    /** What the requester asked for, under the transfer id it chose. */
    public record Request(String transferId, UUID customerId, UUID fromAccountId, UUID toAccountId, Money amount,
            Instant requestedAt) {

        public Request {
            Objects.requireNonNull(transferId, "transferId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(fromAccountId, "fromAccountId");
            Objects.requireNonNull(toAccountId, "toAccountId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }

        /** The same transfer asked for again: everything but the time it was sent. */
        public boolean sameTransferAs(Request other) {
            return transferId.equals(other.transferId) && customerId.equals(other.customerId)
                    && fromAccountId.equals(other.fromAccountId) && toAccountId.equals(other.toAccountId)
                    && amount.equals(other.amount);
        }
    }

    /** Everything that is stored. {@code version} is the optimistic-concurrency token (null until stored). */
    public record State(Request request, Status status, Route route, FailureReason failureReason,
            String failureDetail, Long version) {
    }

    private final Request request;
    private final Long version;
    private Status status;
    private Route route;
    private FailureReason failureReason;
    private String failureDetail;

    private Transfer(State state) {
        this.request = Objects.requireNonNull(state.request(), "request");
        this.status = Objects.requireNonNull(state.status(), "status");
        this.route = state.route();
        this.failureReason = state.failureReason();
        this.failureDetail = state.failureDetail();
        this.version = state.version();
    }

    public static Transfer requested(Request request) {
        return new Transfer(new State(request, Status.AWAITING_ACCOUNTS, null, null, null, null));
    }

    public static Transfer restore(State state) {
        return new Transfer(state);
    }

    /**
     * Starts the saga once both accounts are known: checks that both belong to the customer and that
     * the route is supported, then debits the source.
     *
     * @return {@link Step.Wait} while an account is still unknown
     */
    public Step start(Optional<KnownAccount> from, Optional<KnownAccount> to) {
        require(Status.AWAITING_ACCOUNTS, "start");
        if (from.isEmpty() || to.isEmpty()) {
            return new Step.Wait();
        }
        for (var account : new KnownAccount[] {from.get(), to.get()}) {
            if (!account.customerId().equals(request.customerId())) {
                return fail(FailureReason.INVALID_REQUEST,
                        "account " + account.accountId() + " does not belong to customer " + request.customerId());
            }
        }
        var fromLedger = from.get().ledger();
        var toLedger = to.get().ledger();
        if (fromLedger == Ledger.CURRENT_ACCOUNT && toLedger == Ledger.SAVINGS) {
            route = Route.CURRENT_TO_SAVINGS;
            status = Status.DEBITING;
            return new Step.DebitCurrentAccount(request.fromAccountId(), request.transferId(), request.amount());
        }
        if (fromLedger == Ledger.SAVINGS && toLedger == Ledger.CURRENT_ACCOUNT) {
            route = Route.SAVINGS_TO_CURRENT;
            status = Status.DEBITING;
            return new Step.DebitSavings(request.fromAccountId(), request.transferId(), request.amount());
        }
        return fail(FailureReason.INVALID_REQUEST, "transfers from " + fromLedger + " to " + toLedger
                + " are not supported");
    }

    /** A request whose accounts never became known. */
    public Step timedOut() {
        require(Status.AWAITING_ACCOUNTS, "time out");
        return fail(FailureReason.INVALID_REQUEST, "accounts not known in time");
    }

    /** The source's ledger debited it: credit the target. */
    public Step debited(Ledger by, UUID account) {
        require(Status.DEBITING, "be debited");
        requireAnswerer(by, account, sourceLedger(), request.fromAccountId(), "debited");
        status = Status.CREDITING;
        return route == Route.CURRENT_TO_SAVINGS
                ? new Step.CreditSavings(request.toAccountId(), request.transferId(), request.amount())
                : new Step.CreditCurrentAccount(request.toAccountId(), request.transferId(), request.amount());
    }

    public Step debitRejected(Ledger by, UUID account, String detail) {
        require(Status.DEBITING, "have its debit rejected");
        requireAnswerer(by, account, sourceLedger(), request.fromAccountId(), "rejected the debit of");
        return fail(FailureReason.DEBIT_REJECTED, detail);
    }

    public Step credited(Ledger by, UUID account) {
        require(Status.CREDITING, "be credited");
        requireAnswerer(by, account, targetLedger(), request.toAccountId(), "credited");
        status = Status.COMPLETED;
        return new Step.Completed(request.transferId());
    }

    /** The target refused the money: give it back to the source (compensation). */
    public Step creditRefused(Ledger by, UUID account, String detail) {
        require(Status.CREDITING, "have its credit refused");
        requireAnswerer(by, account, targetLedger(), request.toAccountId(), "refused the credit of");
        if (route != Route.SAVINGS_TO_CURRENT) {
            throw new IllegalTransferStateException("Savings never refuses a credit; transfer "
                    + request.transferId());
        }
        status = Status.RETURNING;
        failureDetail = detail;
        return new Step.ReturnToSavings(request.fromAccountId(), request.transferId());
    }

    /** Savings credited the refused money back: the transfer failed, and no money moved. */
    public Step returned(Ledger by, UUID account) {
        require(Status.RETURNING, "be returned");
        requireAnswerer(by, account, Ledger.SAVINGS, request.fromAccountId(), "returned");
        return fail(FailureReason.CREDIT_REFUSED, failureDetail);
    }

    private Step fail(FailureReason reason, String detail) {
        status = Status.FAILED;
        failureReason = reason;
        failureDetail = detail;
        return new Step.Failed(request.transferId(), reason, detail);
    }

    private Ledger sourceLedger() {
        return route == Route.CURRENT_TO_SAVINGS ? Ledger.CURRENT_ACCOUNT : Ledger.SAVINGS;
    }

    private Ledger targetLedger() {
        return route == Route.CURRENT_TO_SAVINGS ? Ledger.SAVINGS : Ledger.CURRENT_ACCOUNT;
    }

    private void require(Status expected, String action) {
        if (status != expected) {
            throw new IllegalTransferStateException("Transfer " + request.transferId() + " is " + status
                    + " and can't " + action);
        }
    }

    /**
     * An answer counts only from the ledger and the account the instruction went to: a transfer id is
     * the requester's choice, so the same id answered for another account is not this transfer's answer.
     */
    private void requireAnswerer(Ledger by, UUID account, Ledger expectedLedger, UUID expectedAccount, String what) {
        if (by != expectedLedger) {
            throw new IllegalTransferStateException(by + " " + what + " transfer " + request.transferId()
                    + ", but that is " + expectedLedger + "'s part");
        }
        if (!expectedAccount.equals(account)) {
            throw new IllegalTransferStateException("Account " + account + " " + what + " transfer "
                    + request.transferId() + ", but that is account " + expectedAccount + "'s part");
        }
    }

    public State state() {
        return new State(request, status, route, failureReason, failureDetail, version);
    }

    public String transferId() {
        return request.transferId();
    }

    public Request request() {
        return request;
    }

    public Status status() {
        return status;
    }

    public Optional<Route> route() {
        return Optional.ofNullable(route);
    }

    public Optional<FailureReason> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public Optional<String> failureDetail() {
        return Optional.ofNullable(failureDetail);
    }
}
