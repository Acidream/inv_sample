package com.example.transfers.application;

import com.example.transfers.application.port.AccountDirectory;
import com.example.transfers.application.port.ProcessedMessages;
import com.example.transfers.application.port.RequestLog;
import com.example.transfers.application.port.SagaMessages;
import com.example.transfers.application.port.Transfers;
import com.example.transfers.domain.KnownAccount;
import com.example.transfers.domain.Step;
import com.example.transfers.domain.Transfer;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs transfers between a customer's accounts (ADR 0015, 0018). Each input (a request, an account
 * becoming known, a ledger's answer) is one transaction that records the transfer's new state and
 * the message the next step needs, through the outbox.
 *
 * <p>De-duplication works on two levels, as in savings: the message id catches redeliveries; the
 * transfer id catches the same transfer requested again.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final Transfers transfers;
    private final AccountDirectory directory;
    private final ProcessedMessages processed;
    private final SagaMessages messages;
    private final RequestLog requestLog;

    public TransferService(Transfers transfers, AccountDirectory directory, ProcessedMessages processed,
            SagaMessages messages, RequestLog requestLog) {
        this.transfers = transfers;
        this.directory = directory;
        this.processed = processed;
        this.messages = messages;
        this.requestLog = requestLog;
    }

    /**
     * Accepts a request into the command log; changes no state (ADR 0019). Deliberately not
     * transactional: the Kafka send may wait seconds for its acknowledgement, and inside a transaction
     * it would hold a database connection all that time.
     *
     * @return the transfer if it was already applied, empty if it was just appended
     * @throws TransferIdInUseException if the id belongs to a different transfer
     */
    public Optional<Transfer> submit(Transfer.Request request) {
        var applied = transfers.find(request.transferId());
        if (applied.isPresent()) {
            if (!applied.get().request().sameTransferAs(request)) {
                throw new TransferIdInUseException(request.transferId());
            }
            return applied;
        }
        requestLog.append(request);
        return Optional.empty();
    }

    /**
     * Applies a request from the command log. A transfer id already in use for a different transfer is
     * a contract violation: the exception dead-letters the message for a person to look at.
     */
    @Transactional
    public void request(String messageId, Transfer.Request request) {
        if (!processed.markProcessed("request:" + messageId)) {
            return;
        }
        directory.lock(request.fromAccountId(), request.toAccountId());
        var earlier = transfers.find(request.transferId());
        if (earlier.isPresent()) {
            if (!earlier.get().request().sameTransferAs(request)) {
                throw new TransferIdInUseException(request.transferId());
            }
            log.info("Transfer {} was already requested; skipping message {}", request.transferId(), messageId);
            return;
        }
        var transfer = Transfer.requested(request);
        var step = transfer.start(directory.find(request.fromAccountId()), directory.find(request.toAccountId()));
        transfers.add(transfer);
        execute(step);
    }

    /** An account became known: transfers that were waiting for it can start. */
    @Transactional
    public void accountOpened(String messageId, KnownAccount account) {
        if (!processed.markProcessed(messageId)) {
            return;
        }
        directory.lock(account.accountId());
        var known = directory.find(account.accountId());
        if (known.isPresent()) {
            if (!known.get().equals(account)) {
                // Two ledgers, or two customers, claim one account id: routing and the ownership check
                // can't both be right. A person decides (the exception dead-letters the event).
                throw new AccountConflictException(known.get(), account);
            }
            return;
        }
        directory.add(account);
        for (var transfer : transfers.awaiting(account.accountId())) {
            var request = transfer.request();
            var step = transfer.start(directory.find(request.fromAccountId()), directory.find(request.toAccountId()));
            transfers.update(transfer);
            execute(step);
        }
    }

    /**
     * A ledger answered. Answers about transfers this service didn't run (a ledger's own bookings, or
     * instructions sent by someone else) are not its business.
     */
    @Transactional
    public void outcome(LedgerOutcome outcome) {
        if (!processed.markProcessed(outcome.messageId())) {
            return;
        }
        var found = transfers.find(outcome.transferId());
        if (found.isEmpty()) {
            log.debug("Ignoring {} of transfer {}: not one of ours", outcome.kind(), outcome.transferId());
            return;
        }
        var transfer = found.get();
        var step = switch (outcome.kind()) {
            case DEBITED -> transfer.debited(outcome.ledger(), outcome.accountId());
            case DEBIT_REJECTED -> transfer.debitRejected(outcome.ledger(), outcome.accountId(), outcome.detail());
            case CREDITED -> transfer.credited(outcome.ledger(), outcome.accountId());
            case CREDIT_REFUSED -> transfer.creditRefused(outcome.ledger(), outcome.accountId(), outcome.detail());
            case RETURNED -> transfer.returned(outcome.ledger(), outcome.accountId());
        };
        transfers.update(transfer);
        execute(step);
    }

    /** Fails requests whose accounts didn't become known in time. @return how many */
    @Transactional
    public int failAwaitingSince(Instant receivedBefore, int limit) {
        var stale = transfers.awaitingSince(receivedBefore, limit);
        for (var transfer : stale) {
            var step = transfer.timedOut();
            transfers.update(transfer);
            execute(step);
        }
        return stale.size();
    }

    /**
     * Transfers waiting for a ledger's answer since before the given time (ADR 0023). Not re-sent:
     * both ledgers answer a transfer id once, so a re-sent instruction would get no answer. A stuck
     * transfer means a dead letter or a bug, for a person to look at.
     */
    @Transactional(readOnly = true)
    public long countStuck(Instant changedBefore) {
        return transfers.countInFlightSince(changedBefore);
    }

    @Transactional(readOnly = true)
    public Transfer get(String transferId) {
        return transfers.find(transferId).orElseThrow(() -> new UnknownTransferException(transferId));
    }

    private void execute(Step step) {
        if (step instanceof Step.Wait) {
            return;
        }
        messages.send(step);
    }
}
