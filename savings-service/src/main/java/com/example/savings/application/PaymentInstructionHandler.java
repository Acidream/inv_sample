package com.example.savings.application;

import com.example.savings.application.PaymentInstruction.CancelWithdrawal;
import com.example.savings.application.PaymentInstruction.ConfirmWithdrawal;
import com.example.savings.application.PaymentInstruction.Deposit;
import com.example.savings.application.PaymentInstruction.RequestWithdrawal;
import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.Deposits;
import com.example.savings.application.port.Deposits.RecordedDeposit;
import com.example.savings.application.port.Holds;
import com.example.savings.application.port.ProcessedEvents;
import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.domain.AccountEvent.FundsReleased;
import com.example.savings.domain.AccountEvent.FundsReserved;
import com.example.savings.domain.AccountEvent.MoneyDeposited;
import com.example.savings.domain.AccountEvent.MoneyWithdrawn;
import com.example.savings.domain.AccountEvent.RejectionReason;
import com.example.savings.domain.AccountEvent.ReleaseReason;
import com.example.savings.domain.AccountEvent.WithdrawalRejected;
import com.example.savings.domain.Hold;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies instructions from Payments. One instruction = one transaction that contains the
 * de-duplication markers, the state change, the ledger entry and the outgoing event (outbox).
 * Either all of it commits or none of it.
 *
 * <p>De-duplication works on two levels: the message id catches redeliveries of the same message;
 * the business key (payment id, withdrawal request id) catches the same intent re-sent as a new message.
 */
@Service
public class PaymentInstructionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentInstructionHandler.class);

    private final Accounts accounts;
    private final Holds holds;
    private final Bookkeeping bookkeeping;
    private final ProcessedEvents processedEvents;
    private final Deposits deposits;
    private final AccountEventPublisher events;
    private final SavingsMetrics metrics;
    private final SavingsPolicy policy;
    private final Clock clock;

    public PaymentInstructionHandler(Accounts accounts, Holds holds, Bookkeeping bookkeeping,
            ProcessedEvents processedEvents, Deposits deposits, AccountEventPublisher events, SavingsMetrics metrics,
            SavingsPolicy policy, Clock clock) {
        this.accounts = accounts;
        this.holds = holds;
        this.bookkeeping = bookkeeping;
        this.processedEvents = processedEvents;
        this.deposits = deposits;
        this.events = events;
        this.metrics = metrics;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public void handle(PaymentInstruction instruction) {
        if (!processedEvents.markProcessed(instruction.eventId())) {
            log.info("Skipping already processed message {}", instruction.eventId());
            metrics.duplicateMessage();
            return;
        }
        switch (instruction) {
            case Deposit deposit -> deposit(deposit);
            case RequestWithdrawal request -> requestWithdrawal(request);
            case ConfirmWithdrawal confirm -> confirmWithdrawal(confirm);
            case CancelWithdrawal cancel -> cancelWithdrawal(cancel);
        }
    }

    private void deposit(Deposit deposit) {
        var account = accounts.get(deposit.accountId());
        var now = clock.instant();
        var incoming = new RecordedDeposit(deposit.paymentId(), account.id(), deposit.amount(), deposit.eventId(),
                now);
        var earlier = deposits.recordIfNew(incoming);
        if (earlier.isPresent()) {
            skipResentPayment(incoming, earlier.get());
            return;
        }
        var entry = account.deposit(deposit.amount(), deposit.paymentId(), now);
        bookkeeping.book(entry);
        events.publish(new MoneyDeposited(account.id(), deposit.paymentId(), deposit.amount(), account.balance()));
        metrics.deposited(deposit.amount());
    }

    /**
     * A payment that was already credited, arriving under a new message id. Identical money: a re-send,
     * skipped. Different account or amount: Payments reused a payment id, which is a contract violation
     * that a person must look at (the exception sends the message to the dead-letter topic).
     */
    private void skipResentPayment(RecordedDeposit incoming, RecordedDeposit earlier) {
        if (!incoming.sameMoneyAs(earlier)) {
            throw new IllegalArgumentException("Payment %s was already credited as %s to account %s; message %s"
                    .formatted(earlier.paymentId(), earlier.amount().amount(), earlier.accountId(), incoming.eventId())
                    + " asks for %s to account %s".formatted(incoming.amount().amount(), incoming.accountId()));
        }
        log.info("Payment {} was already credited by message {}; skipping message {}", earlier.paymentId(),
                earlier.eventId(), incoming.eventId());
        metrics.duplicateMessage();
    }

    private void requestWithdrawal(RequestWithdrawal request) {
        if (holds.findByRequestId(request.requestId()).isPresent()) {
            metrics.duplicateMessage();
            return;
        }
        var account = accounts.get(request.accountId());
        if (!account.canReserve(request.amount())) {
            events.publish(new WithdrawalRejected(account.id(), request.requestId(), request.amount(),
                    RejectionReason.INSUFFICIENT_FUNDS));
            metrics.withdrawal("rejected");
            return;
        }
        account.reserve(request.amount());
        holds.add(Hold.reserve(account.id(), request.requestId(), request.amount(), clock.instant(),
                policy.holdTtl()));
        events.publish(new FundsReserved(account.id(), request.requestId(), request.amount(),
                account.availableBalance()));
        metrics.withdrawal("reserved");
    }

    private void confirmWithdrawal(ConfirmWithdrawal confirm) {
        var hold = holdFor(confirm.requestId(), confirm);
        var account = accounts.get(hold.accountId());
        var now = clock.instant();
        var outcome = hold.confirm(now);
        if (outcome == Hold.Outcome.NO_OP) {
            return;
        }
        var entry = outcome == Hold.Outcome.APPLIED
                ? account.settleHold(hold.amount(), hold.requestId(), now)
                // The hold had expired and its funds were released: the money has left the bank anyway.
                : account.debitAfterExpiredHold(hold.amount(), hold.requestId(), now);
        bookkeeping.book(entry);
        events.publish(new MoneyWithdrawn(account.id(), hold.requestId(), hold.amount(), account.balance()));
        metrics.withdrawal("settled");
        if (account.balance().isNegative()) {
            log.warn("Account {} is overdrawn ({}) after late confirmation of {}", account.id(), account.balance(),
                    hold.requestId());
            metrics.negativeBalance();
        }
    }

    private void cancelWithdrawal(CancelWithdrawal cancel) {
        var hold = holdFor(cancel.requestId(), cancel);
        if (hold.cancel(clock.instant()) != Hold.Outcome.APPLIED) {
            return;
        }
        var account = accounts.get(hold.accountId());
        account.releaseHold(hold.amount());
        events.publish(new FundsReleased(account.id(), hold.requestId(), hold.amount(), ReleaseReason.CANCELLED));
        metrics.withdrawal("released");
    }

    private Hold holdFor(String requestId, PaymentInstruction instruction) {
        var hold = holds.findByRequestId(requestId).orElseThrow(() -> new UnknownWithdrawalException(requestId));
        if (!hold.accountId().equals(instruction.accountId())) {
            throw new IllegalArgumentException("Withdrawal " + requestId + " does not belong to account "
                    + instruction.accountId());
        }
        return hold;
    }
}
