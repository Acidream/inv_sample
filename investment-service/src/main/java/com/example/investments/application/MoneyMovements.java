package com.example.investments.application;

import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Broker;
import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.PortfolioEvents;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.application.port.TransferAnswers;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.TradingCalendar;
import com.example.investments.domain.TransferAnswer;
import com.example.investments.domain.TransferAnswer.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money between portfolios and current accounts (ADR 0026, 0028). The portfolio is a ledger the
 * transfer service instructs like savings: money for a buy comes in, refunds and proceeds go out, a
 * payout the current account refused comes back. The outcomes of the transfers investments
 * requested move its orders and payouts on.
 *
 * <p>De-duplication on two levels, as everywhere: the message id catches redeliveries; the transfer
 * id (per portfolio and kind of instruction) catches the same instruction sent again.
 */
@Service
public class MoneyMovements {

    private static final Logger log = LoggerFactory.getLogger(MoneyMovements.class);

    private final Portfolios portfolios;
    private final Orders orders;
    private final Payouts payouts;
    private final TransferAnswers answers;
    private final ProcessedMessages processed;
    private final PortfolioEvents events;
    private final Broker broker;
    private final Books books;
    private final PayoutDesk desk;
    private final InvestmentMetrics metrics;

    MoneyMovements(Portfolios portfolios, Orders orders, Payouts payouts, TransferAnswers answers,
            ProcessedMessages processed, PortfolioEvents events, Broker broker, Books books, PayoutDesk desk,
            InvestmentMetrics metrics) {
        this.portfolios = portfolios;
        this.orders = orders;
        this.payouts = payouts;
        this.answers = answers;
        this.processed = processed;
        this.events = events;
        this.broker = broker;
        this.books = books;
        this.desk = desk;
        this.metrics = metrics;
    }

    // --- instructions from the transfer service ---------------------------------------------------

    /**
     * Answers an instruction exactly once.
     *
     * @throws IllegalInstructionException if it contradicts an earlier one (dead-lettered)
     */
    @Transactional
    public void apply(Instruction instruction) {
        if (!processed.markProcessed("instruction:" + instruction.messageId())) {
            log.info("Skipping already processed instruction {}", instruction.messageId());
            metrics.duplicateMessage();
            return;
        }
        var portfolio = portfolios.lock(instruction.portfolioId());
        var earlier = answers.find(instruction.portfolioId(), instruction.transferId(), instruction.kind());
        if (earlier.isPresent()) {
            if (instruction.amount() != null && !earlier.get().amount().equals(instruction.amount())) {
                throw new IllegalInstructionException("Transfer " + instruction.transferId() + " was answered as a "
                        + instruction.kind() + " of " + earlier.get().amount() + ", now sent for "
                        + instruction.amount() + "; message " + instruction.messageId());
            }
            log.info("Transfer {} was already answered; skipping message {}", instruction.transferId(),
                    instruction.messageId());
            metrics.duplicateMessage();
            return;
        }
        if (portfolio.isEmpty()) {
            // The transfer service only instructs portfolios it learnt from PortfolioOpened.
            throw new IllegalInstructionException("Instruction " + instruction.messageId() + " for unknown portfolio "
                    + instruction.portfolioId());
        }
        var ledger = books.of(portfolio.get());
        Runnable apply = switch (instruction.kind()) {
            case DEPOSIT -> () -> deposit(ledger, instruction);
            case WITHDRAWAL -> () -> withdraw(ledger, instruction);
            case RETURN -> () -> returned(ledger, instruction);
        };
        apply.run();
        ledger.save();
    }

    /**
     * Money arrived. If it is the buy that waits for it, the buy goes to the broker; anything else
     * (a transfer someone else requested to the portfolio) goes straight back.
     */
    private void deposit(Books.Ledger ledger, Instruction instruction) {
        var portfolio = ledger.portfolio();
        var at = instruction.issuedAt();
        var waiting = Order.orderOfFundingTransfer(instruction.transferId())
                .flatMap(orders::find)
                .filter(found -> found.portfolioId().equals(portfolio.id()))
                .filter(found -> found.status() == Order.Status.FUNDING);
        var order = waiting.filter(found -> found.awaitsFunding(instruction.amount()));
        ledger.book(CashEntry.Booking.funding(instruction.transferId(), instruction.amount(),
                order.map(Order::id).orElse(null), at));
        if (order.isPresent()) {
            order.get().funded(TradingCalendar.tradingDayOf(at));
            orders.update(order.get());
            broker.place(order.get(), at);
        } else {
            log.warn("Transfer {} brought {} to portfolio {} without an order waiting for it; paying it back",
                    instruction.transferId(), instruction.amount(), portfolio.id());
            waiting.ifPresent(mismatched -> fundingDidNotMatch(mismatched, instruction.amount()));
            desk.payOut(portfolio, Payout.unexpectedDeposit(portfolio.id(), instruction.transferId(),
                    instruction.amount(), at), at);
        }
        answer(instruction.carriedOut(instruction.amount()));
    }

    /**
     * The order's funding transfer brought another amount than the order asked for. That money goes
     * back on its own; the order can't wait for a second funding under the same transfer id, so it ends.
     */
    private void fundingDidNotMatch(Order order, Money amount) {
        log.warn("Order {} failed: its funding brought {} instead of {}", order.id(), amount,
                order.request().amount());
        order.fundingFailed(Order.FUNDING_MISMATCH);
        orders.update(order);
        metrics.orderEnded(order);
    }

    /** Money leaves only for a payout investments requested, under that payout's current transfer id. */
    private void withdraw(Books.Ledger ledger, Instruction instruction) {
        var portfolio = ledger.portfolio();
        var at = instruction.issuedAt();
        var payout = payouts.findByTransfer(instruction.transferId())
                .filter(found -> found.portfolioId().equals(portfolio.id()))
                .filter(found -> found.awaits(instruction.transferId(), instruction.amount()));
        if (payout.isEmpty()) {
            log.warn("Rejecting withdrawal {} of {} from portfolio {}: {}", instruction.transferId(),
                    instruction.amount(), portfolio.id(), TransferAnswer.NOTHING_OWED);
            answer(instruction.rejected(TransferAnswer.NOTHING_OWED));
            return;
        }
        ledger.book(CashEntry.Booking.payout(instruction.transferId(), instruction.amount(),
                payout.get().orderId().orElse(null), at));
        answer(instruction.carriedOut(instruction.amount()));
    }

    /** The current account refused a payout: the money is back, and the payout becomes unpaid. */
    private void returned(Books.Ledger ledger, Instruction instruction) {
        var portfolio = ledger.portfolio();
        var withdrawal = answers.find(portfolio.id(), instruction.transferId(), Kind.WITHDRAWAL)
                .filter(TransferAnswer::carriedOut)
                .orElseThrow(() -> new IllegalInstructionException("Transfer " + instruction.transferId()
                        + " is returned to portfolio " + portfolio.id() + " but never left it"));
        var orderId = payouts.findByTransfer(instruction.transferId()).flatMap(Payout::orderId).orElse(null);
        ledger.book(CashEntry.Booking.payoutReturned(instruction.transferId(), withdrawal.amount(), orderId,
                instruction.issuedAt()));
        answer(instruction.carriedOut(withdrawal.amount()));
    }

    private void answer(TransferAnswer answer) {
        answers.add(answer);
        events.answered(answer);
    }

    // --- outcomes of the transfers investments requested ------------------------------------------

    /** Everything else on the transfer events topic is not investments' business. */
    @Transactional
    public void outcome(TransferOutcome outcome) {
        var funded = Order.orderOfFundingTransfer(outcome.transferId());
        if (funded.isEmpty() && !Payout.isPayoutTransfer(outcome.transferId())) {
            return;
        }
        if (!processed.markProcessed("transfer-event:" + outcome.messageId())) {
            metrics.duplicateMessage();
            return;
        }
        if (funded.isPresent()) {
            fundingOutcome(funded.get(), outcome);
        } else {
            payoutOutcome(outcome);
        }
    }

    /** A completed funding needs nothing: the order went to the broker when the money arrived. */
    private void fundingOutcome(String orderId, TransferOutcome outcome) {
        if (outcome.completed()) {
            return;
        }
        var locked = underItsPortfoliosLock(() -> orders.find(orderId), Order::portfolioId);
        if (locked.isEmpty()) {
            log.warn("Funding transfer {} failed for unknown order {}", outcome.transferId(), orderId);
            return;
        }
        var order = locked.get();
        if (order.status() != Order.Status.FUNDING) {
            log.warn("Funding transfer {} failed, but order {} is {}; ignored", outcome.transferId(), orderId,
                    order.status());
            return;
        }
        log.info("Order {} failed: its money didn't come ({})", orderId, outcome.explanation());
        order.fundingFailed(outcome.explanation());
        orders.update(order);
        metrics.orderEnded(order);
    }

    private void payoutOutcome(TransferOutcome outcome) {
        var locked = underItsPortfoliosLock(() -> payouts.findByTransfer(outcome.transferId()),
                Payout::portfolioId);
        if (locked.isEmpty()) {
            log.warn("Outcome of unknown payout transfer {}; ignored", outcome.transferId());
            return;
        }
        var payout = locked.get();
        if (payout.status() != Payout.Status.REQUESTED || !payout.transferId().equals(outcome.transferId())) {
            log.warn("Outcome of payout transfer {} arrived, but payout {} is {}; ignored", outcome.transferId(),
                    payout.id(), payout.status());
            return;
        }
        if (outcome.completed()) {
            desk.paid(payout);
        } else {
            log.warn("Payout {} of {} could not be paid: {}", payout.id(), payout.amount(), outcome.explanation());
            desk.unpaid(payout, outcome.at());
        }
    }

    // --- unpaid payouts ---------------------------------------------------------------------------

    /** A current account was unblocked: payouts it refused are tried again. */
    @Transactional
    public void accountUnblocked(String messageId, UUID accountId, Instant at) {
        if (!processed.markProcessed("current-account:" + messageId)) {
            metrics.duplicateMessage();
            return;
        }
        for (var payoutId : payouts.unpaidIds(accountId, Integer.MAX_VALUE)) {
            retry(payoutId, at);
        }
    }

    /** The daily retry. @return how many payouts were tried again */
    @Transactional
    public int retryUnpaid(Instant at, int limit) {
        List<UUID> ids = payouts.unpaidIds(null, limit);
        ids.forEach(id -> retry(id, at));
        return ids.size();
    }

    private void retry(UUID payoutId, Instant at) {
        var found = payouts.find(payoutId).orElseThrow();
        Portfolio portfolio = portfolios.lock(found.portfolioId()).orElseThrow();
        var payout = payouts.find(payoutId).orElseThrow();
        if (payout.status() != Payout.Status.UNPAID) {
            return;
        }
        log.info("Retrying payout {} of {} to {} (attempt {})", payout.id(), payout.amount(),
                portfolio.referenceAccountId(), payout.attempt() + 1);
        desk.retry(portfolio, payout, at);
    }

    /**
     * Reads what belongs to a portfolio, locks that portfolio, and reads it again under the lock:
     * another message for the same portfolio may have changed it meanwhile.
     */
    private <T> Optional<T> underItsPortfoliosLock(Supplier<Optional<T>> read, Function<T, UUID> portfolioOf) {
        var found = read.get();
        if (found.isEmpty()) {
            return found;
        }
        portfolios.lock(portfolioOf.apply(found.get()));
        return read.get();
    }
}
