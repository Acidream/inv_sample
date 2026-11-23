package com.example.savings.application;

import com.example.savings.application.PaymentInstruction.ReturnTransfer;
import com.example.savings.application.PaymentInstruction.TransferIn;
import com.example.savings.application.PaymentInstruction.TransferOut;
import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.ProcessedEvents;
import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.application.port.Transfers;
import com.example.savings.domain.AccountEvent.MoneyDeposited;
import com.example.savings.domain.AccountEvent.MoneyWithdrawn;
import com.example.savings.domain.AccountEvent.RejectionReason;
import com.example.savings.domain.AccountEvent.WithdrawalRejected;
import com.example.savings.domain.AccountEvent.WithdrawalReturned;
import com.example.savings.domain.IllegalTransferException;
import com.example.savings.domain.Money;
import com.example.savings.domain.Transfer;
import com.example.savings.domain.Transfer.Direction;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies transfer instructions from Payments. One instruction = one transaction that contains the
 * de-duplication markers, the balance change, the ledger entry, the posting and the outgoing event
 * (outbox). Either all of it commits or none of it.
 *
 * <p>Money only moves to and from the customer's own current account, so every instruction has a
 * known outcome at once: no holds, no waiting for the outside world, and the balance never goes
 * negative.
 *
 * <p>De-duplication works on two levels: the message id catches redeliveries of the same message
 * (stored as {@code "instruction:" + id}, like every consumed id prefixed by its source); the
 * transfer id catches the same transfer re-sent as a new message.
 */
@Service
public class PaymentInstructionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentInstructionHandler.class);

    private final Accounts accounts;
    private final Transfers transfers;
    private final Bookkeeping bookkeeping;
    private final ProcessedEvents processedEvents;
    private final AccountEventPublisher events;
    private final SavingsMetrics metrics;
    private final Clock clock;

    public PaymentInstructionHandler(Accounts accounts, Transfers transfers, Bookkeeping bookkeeping,
            ProcessedEvents processedEvents, AccountEventPublisher events, SavingsMetrics metrics, Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.bookkeeping = bookkeeping;
        this.processedEvents = processedEvents;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void handle(PaymentInstruction instruction) {
        if (!processedEvents.markProcessed("instruction:" + instruction.eventId())) {
            log.info("Skipping already processed message {}", instruction.eventId());
            metrics.duplicateMessage();
            return;
        }
        switch (instruction) {
            case TransferIn in -> transferIn(in);
            case TransferOut out -> transferOut(out);
            case ReturnTransfer returned -> returnTransfer(returned);
        }
    }

    private void transferIn(TransferIn in) {
        var account = accounts.get(in.accountId());
        if (alreadyApplied(in.transferId(), Direction.IN, account.id(), in.amount(), in.eventId())) {
            return;
        }
        var now = clock.instant();
        bookkeeping.book(account.deposit(in.amount(), in.transferId(), now));
        accounts.update(account);
        transfers.add(Transfer.deposited(in.transferId(), account.id(), in.amount(), now));
        events.publish(new MoneyDeposited(account.id(), in.transferId(), in.amount(), account.balance()));
        metrics.deposited(in.amount());
    }

    private void transferOut(TransferOut out) {
        var account = accounts.get(out.accountId());
        if (alreadyApplied(out.transferId(), Direction.OUT, account.id(), out.amount(), out.eventId())) {
            return;
        }
        var now = clock.instant();
        if (!account.canWithdraw(out.amount())) {
            transfers.add(Transfer.rejected(out.transferId(), account.id(), out.amount(), now));
            events.publish(new WithdrawalRejected(account.id(), out.transferId(), out.amount(),
                    RejectionReason.INSUFFICIENT_FUNDS));
            metrics.withdrawal("rejected");
            return;
        }
        bookkeeping.book(account.withdraw(out.amount(), out.transferId(), now));
        accounts.update(account);
        transfers.add(Transfer.withdrawn(out.transferId(), account.id(), out.amount(), now));
        events.publish(new MoneyWithdrawn(account.id(), out.transferId(), out.amount(), account.balance()));
        metrics.withdrawal("withdrawn");
    }

    private void returnTransfer(ReturnTransfer returned) {
        var transfer = transfers.find(returned.transferId())
                .orElseThrow(() -> new UnknownTransferException(returned.transferId()));
        if (!transfer.accountId().equals(returned.accountId())) {
            throw new IllegalTransferException("Transfer " + transfer.transferId() + " does not belong to account "
                    + returned.accountId());
        }
        var now = clock.instant();
        if (!transfer.markReturned(now)) {
            log.info("Transfer {} was already returned; skipping message {}", transfer.transferId(),
                    returned.eventId());
            metrics.duplicateMessage();
            return;
        }
        transfers.update(transfer);
        var account = accounts.get(transfer.accountId());
        bookkeeping.book(account.creditReturnedWithdrawal(transfer.amount(), transfer.transferId(), now));
        accounts.update(account);
        events.publish(new WithdrawalReturned(account.id(), transfer.transferId(), transfer.amount(),
                account.balance()));
        metrics.withdrawal("returned");
    }

    /**
     * A transfer id seen before. The same movement again: a re-send, skipped (a rejected withdrawal stays
     * rejected; a new attempt needs a new transfer id). A different movement under the same id: Payments
     * reused an id, a contract violation that a person must look at (the exception dead-letters the message).
     */
    private boolean alreadyApplied(String transferId, Direction direction, UUID accountId, Money amount,
            String eventId) {
        var earlier = transfers.find(transferId);
        if (earlier.isEmpty()) {
            return false;
        }
        var transfer = earlier.get();
        if (!transfer.describes(direction, accountId, amount)) {
            throw new IllegalTransferException("Transfer %s is already known as %s %s for account %s; message %s"
                    .formatted(transferId, transfer.direction(), transfer.amount().amount(), transfer.accountId(),
                            eventId)
                    + " describes %s %s for account %s".formatted(direction, amount.amount(), accountId));
        }
        log.info("Transfer {} was already applied ({}); skipping message {}", transferId, transfer.status(), eventId);
        metrics.duplicateMessage();
        return true;
    }
}
