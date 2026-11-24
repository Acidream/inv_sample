package com.example.currentaccount.application;

import com.example.currentaccount.application.port.Bookings;
import com.example.currentaccount.application.port.CommandLog;
import com.example.currentaccount.application.port.CurrentAccounts;
import com.example.currentaccount.application.port.IncomingPayments;
import com.example.currentaccount.application.port.LedgerEvents;
import com.example.currentaccount.application.port.ProcessedMessages;
import com.example.currentaccount.application.port.TransferAnswers;
import com.example.currentaccount.domain.Booking;
import com.example.currentaccount.domain.CurrentAccount;
import com.example.currentaccount.domain.IncomingPayment;
import com.example.currentaccount.domain.TransferAnswer;
import com.example.currentaccount.domain.TransferAnswer.Direction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current account ledger (ADR 0021). Two kinds of input change it, each in one transaction
 * together with the events it publishes through the outbox:
 *
 * <ul>
 *   <li>commands from its own command log (opening, incoming payments, blocking), accepted by REST;
 *   <li>instructions from the transfer service (debit or credit for a transfer).
 * </ul>
 *
 * <p>De-duplication works on two levels, as in the other services: the message id catches
 * redeliveries; the payment id and the transfer id catch the same thing asked again.
 */
@Service
public class CurrentAccountService {

    private static final Logger log = LoggerFactory.getLogger(CurrentAccountService.class);

    private final CurrentAccounts accounts;
    private final Bookings bookings;
    private final TransferAnswers answers;
    private final IncomingPayments payments;
    private final ProcessedMessages processed;
    private final LedgerEvents events;
    private final CommandLog commandLog;

    public CurrentAccountService(CurrentAccounts accounts, Bookings bookings, TransferAnswers answers,
            IncomingPayments payments, ProcessedMessages processed, LedgerEvents events, CommandLog commandLog) {
        this.accounts = accounts;
        this.bookings = bookings;
        this.answers = answers;
        this.payments = payments;
        this.processed = processed;
        this.events = events;
        this.commandLog = commandLog;
    }

    // --- intake: accepts into the command log, changes nothing (ADR 0019) -------------------------
    // Deliberately not transactional: each lookup is one statement, and the Kafka send may wait seconds
    // for its acknowledgement. Inside a transaction it would hold a database connection all that time,
    // and a slow Kafka would take every connection the consumers and the outbox need.

    /**
     * @return the account if it was already opened, empty if the command was just appended
     * @throws AccountIdInUseException if the id belongs to an account opened with different details
     */
    public Optional<CurrentAccount> submit(Command.Open open) {
        var existing = accounts.find(open.accountId());
        if (existing.isPresent()) {
            if (!existing.get().openedAs(open.customerId(), open.overdraftLimit())) {
                throw new AccountIdInUseException(open.accountId());
            }
            return existing;
        }
        commandLog.append(open);
        return Optional.empty();
    }

    /**
     * @return the payment if it was already applied, empty if the command was just appended
     * @throws PaymentIdInUseException if the id belongs to a different payment
     */
    public Optional<IncomingPayment> submit(Command.ReceivePayment payment) {
        var existing = payments.find(payment.paymentId());
        if (existing.isPresent()) {
            if (!existing.get().samePaymentAs(payment.accountId(), payment.amount())) {
                throw new PaymentIdInUseException(payment.paymentId());
            }
            return existing;
        }
        commandLog.append(payment);
        return Optional.empty();
    }

    /** @throws UnknownAccountException if the account isn't open (yet) */
    public void submit(Command.Block block) {
        appendForExistingAccount(block);
    }

    /** @throws UnknownAccountException if the account isn't open (yet) */
    public void submit(Command.Unblock unblock) {
        appendForExistingAccount(unblock);
    }

    private void appendForExistingAccount(Command command) {
        if (accounts.find(command.accountId()).isEmpty()) {
            throw new UnknownAccountException(command.accountId());
        }
        commandLog.append(command);
    }

    // --- applying commands ------------------------------------------------------------------------

    /**
     * Applies a command from the command log. An account id or payment id already in use with
     * different details is a conflict a person must look at: the exception dead-letters the command.
     */
    @Transactional
    public void apply(String commandId, Command command) {
        if (!processed.markProcessed("command:" + commandId)) {
            log.info("Skipping already applied command {}", commandId);
            return;
        }
        switch (command) {
            case Command.Open open -> open(open);
            case Command.ReceivePayment payment -> receive(payment);
            case Command.Block block -> changeBlock(block, account -> account.block(block.reason()));
            case Command.Unblock unblock -> changeBlock(unblock, CurrentAccount::unblock);
        }
    }

    private void open(Command.Open open) {
        var existing = accounts.lock(open.accountId());
        if (existing.isPresent()) {
            // Different details: two openings under one key were accepted before either was applied.
            // The second one's client was told it was accepted, so it is not dropped silently.
            if (!existing.get().openedAs(open.customerId(), open.overdraftLimit())) {
                throw new AccountIdInUseException(open.accountId());
            }
            log.info("Account {} is already open; skipping the repeated opening", open.accountId());
            return;
        }
        var account = CurrentAccount.open(open.accountId(), open.customerId(), open.overdraftLimit(),
                open.acceptedAt());
        accounts.add(account);
        events.opened(account);
    }

    private void receive(Command.ReceivePayment command) {
        var earlier = payments.find(command.paymentId());
        if (earlier.isPresent()) {
            if (!earlier.get().samePaymentAs(command.accountId(), command.amount())) {
                throw new PaymentIdInUseException(command.paymentId());
            }
            log.info("Payment {} was already received; skipping", command.paymentId());
            return;
        }
        var account = accounts.lock(command.accountId());
        if (account.isEmpty()) {
            log.warn("Payment {} is for unknown account {}; rejected", command.paymentId(), command.accountId());
            payments.add(IncomingPayment.toUnknownAccount(command.paymentId(), command.accountId(), command.amount(),
                    command.acceptedAt()));
            return;
        }
        var booking = account.get().receive(command.paymentId(), command.amount(), command.acceptedAt());
        if (booking.isEmpty()) {
            log.warn("Payment {} would take account {} beyond its balance limit; rejected", command.paymentId(),
                    command.accountId());
            payments.add(IncomingPayment.rejected(command.paymentId(), command.accountId(), command.amount(),
                    CurrentAccount.BALANCE_LIMIT_EXCEEDED, command.acceptedAt()));
            return;
        }
        accounts.update(account.get());
        bookings.add(booking.get());
        payments.add(IncomingPayment.booked(command.paymentId(), command.accountId(), command.amount(),
                command.acceptedAt()));
    }

    private void changeBlock(Command command, Consumer<CurrentAccount> change) {
        var account = accounts.lock(command.accountId());
        if (account.isEmpty()) {
            log.warn("Ignoring a block change for unknown account {}", command.accountId());
            return;
        }
        change.accept(account.get());
        accounts.update(account.get());
        events.blockChanged(account.get(), command.acceptedAt());
    }

    // --- instructions from the transfer service ---------------------------------------------------

    /**
     * Debits or credits the account for a transfer and answers. A transfer id already answered for
     * another direction or amount is a contract violation: the exception dead-letters the message.
     */
    @Transactional
    public void apply(Instruction instruction) {
        if (!processed.markProcessed("instruction:" + instruction.messageId())) {
            log.info("Skipping already processed instruction {}", instruction.messageId());
            return;
        }
        var account = accounts.lock(instruction.accountId());
        var earlier = answers.find(instruction.accountId(), instruction.transferId());
        if (earlier.isPresent()) {
            if (!earlier.get().answers(instruction.direction(), instruction.amount())) {
                throw new TransferIdInUseException("Transfer " + instruction.transferId() + " was already answered as "
                        + earlier.get().direction() + " " + earlier.get().amount() + " for account "
                        + instruction.accountId() + "; message " + instruction.messageId());
            }
            log.info("Transfer {} was already answered; skipping message {}", instruction.transferId(),
                    instruction.messageId());
            return;
        }
        if (account.isEmpty()) {
            answer(TransferAnswer.rejected(instruction.accountId(), instruction.transferId(), instruction.direction(),
                    instruction.amount(), TransferAnswer.UNKNOWN_ACCOUNT, instruction.issuedAt()));
            return;
        }
        var decision = instruction.direction() == Direction.DEBIT
                ? account.get().debit(instruction.transferId(), instruction.amount(), instruction.issuedAt())
                : account.get().credit(instruction.transferId(), instruction.amount(), instruction.issuedAt());
        decision.booking().ifPresent(booking -> {
            accounts.update(account.get());
            bookings.add(booking);
        });
        answer(decision.answer());
    }

    private void answer(TransferAnswer answer) {
        answers.add(answer);
        events.answered(answer);
    }

    // --- queries ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CurrentAccount get(UUID accountId) {
        return accounts.find(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
    }

    @Transactional(readOnly = true)
    public List<Booking> bookings(UUID accountId, int limit) {
        get(accountId);
        return bookings.latest(accountId, limit);
    }

    @Transactional(readOnly = true)
    public IncomingPayment payment(UUID accountId, String paymentId) {
        return payments.find(paymentId)
                .filter(payment -> payment.accountId().equals(accountId))
                .orElseThrow(() -> new UnknownPaymentException(paymentId));
    }
}
