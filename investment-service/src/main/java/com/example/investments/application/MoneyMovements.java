package com.example.investments.application;

import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Outbound;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.application.port.TransferAnswers;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.TradingCalendar;
import com.example.investments.domain.TransferAnswer;
import com.example.investments.domain.TransferAnswer.Kind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    private final Outbound outbound;
    private final Books books;
    private final PayoutDesk desk;

    MoneyMovements(Portfolios portfolios, Orders orders, Payouts payouts, TransferAnswers answers,
            ProcessedMessages processed, Outbound outbound, Books books, PayoutDesk desk) {
        this.portfolios = portfolios;
        this.orders = orders;
        this.payouts = payouts;
        this.answers = answers;
        this.processed = processed;
        this.outbound = outbound;
        this.books = books;
        this.desk = desk;
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
        var order = Order.orderOfFundingTransfer(instruction.transferId())
                .flatMap(orders::find)
                .filter(found -> found.request().portfolioId().equals(portfolio.id()))
                .filter(found -> found.awaitsFunding(instruction.amount()));
        ledger.credit(CashEntry.Type.FUNDING, instruction.amount(), instruction.transferId(),
                order.map(Order::id).orElse(null), null, at);
        if (order.isPresent()) {
            order.get().funded(TradingCalendar.tradingDayOf(at));
            orders.update(order.get());
            outbound.placeWithBroker(order.get(), at);
        } else {
            log.warn("Transfer {} brought {} to portfolio {} without an order waiting for it; paying it back",
                    instruction.transferId(), instruction.amount(), portfolio.id());
            desk.payOut(portfolio, Payout.unexpectedDeposit(portfolio.id(), instruction.transferId(),
                    instruction.amount(), at), at);
        }
        answer(new TransferAnswer(portfolio.id(), instruction.transferId(), Kind.DEPOSIT, instruction.amount(), null,
                at));
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
            answer(new TransferAnswer(portfolio.id(), instruction.transferId(), Kind.WITHDRAWAL, instruction.amount(),
                    TransferAnswer.NOTHING_OWED, at));
            return;
        }
        ledger.debit(CashEntry.Type.PAYOUT, instruction.amount(), instruction.transferId(),
                payout.get().orderId().orElse(null), null, at);
        answer(new TransferAnswer(portfolio.id(), instruction.transferId(), Kind.WITHDRAWAL, instruction.amount(),
                null, at));
    }

    /** The current account refused a payout: the money is back, and the payout becomes unpaid. */
    private void returned(Books.Ledger ledger, Instruction instruction) {
        var portfolio = ledger.portfolio();
        var withdrawal = answers.find(portfolio.id(), instruction.transferId(), Kind.WITHDRAWAL)
                .filter(TransferAnswer::carriedOut)
                .orElseThrow(() -> new IllegalInstructionException("Transfer " + instruction.transferId()
                        + " is returned to portfolio " + portfolio.id() + " but never left it"));
        var orderId = payouts.findByTransfer(instruction.transferId()).flatMap(Payout::orderId).orElse(null);
        ledger.credit(CashEntry.Type.PAYOUT_RETURN, withdrawal.amount(), instruction.transferId(), orderId, null,
                instruction.issuedAt());
        answer(new TransferAnswer(portfolio.id(), instruction.transferId(), Kind.RETURN, withdrawal.amount(), null,
                instruction.issuedAt()));
    }

    private void answer(TransferAnswer answer) {
        answers.add(answer);
        outbound.answered(answer);
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
        var found = orders.find(orderId);
        if (found.isEmpty()) {
            log.warn("Funding transfer {} failed for unknown order {}", outcome.transferId(), orderId);
            return;
        }
        portfolios.lock(found.get().request().portfolioId());
        var order = orders.find(orderId).orElseThrow();
        if (order.status() != Order.Status.FUNDING) {
            log.warn("Funding transfer {} failed, but order {} is {}; ignored", outcome.transferId(), orderId,
                    order.status());
            return;
        }
        log.info("Order {} failed: its money didn't come ({})", orderId, outcome.explanation());
        order.fundingFailed(outcome.explanation());
        orders.update(order);
    }

    private void payoutOutcome(TransferOutcome outcome) {
        var found = payouts.findByTransfer(outcome.transferId());
        if (found.isEmpty()) {
            log.warn("Outcome of unknown payout transfer {}; ignored", outcome.transferId());
            return;
        }
        portfolios.lock(found.get().portfolioId());
        var payout = payouts.find(found.get().id()).orElseThrow();
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
}
