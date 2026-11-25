package com.example.investments.adapter.web;

import com.example.investments.application.Command;
import com.example.investments.application.InvestmentService;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Fill;
import com.example.investments.domain.Holding;
import com.example.investments.domain.Instrument;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Price;
import com.example.investments.domain.Quantity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portfolios, orders and instruments (ADR 0026). Opening a portfolio and placing an order only accept
 * a command into the command log and answer {@code 202} (ADR 0019); their ids are derived from the
 * {@link IdempotencyKey}, so a retry with the same key is safe (ADR 0025). Money moves between the
 * portfolio and the current account through the transfer service, never through this API.
 */
@RestController
class InvestmentController {

    static final String ORDER_ID = "ord-[0-9a-f-]{36}";
    private static final String ISIN = "[A-Z]{2}[A-Z0-9]{9}[0-9]";
    private static final String POSITIVE_AMOUNT = "(?!0+(\\.0{1,2})?$)\\d{1,17}(\\.\\d{1,2})?";
    private static final String POSITIVE_QUANTITY = "(?!0+(\\.0{1,6})?$)\\d{1,18}(\\.\\d{1,6})?";

    record OpenRequest(@NotNull UUID customerId, @NotNull UUID referenceAccountId) {
    }

    /**
     * @param amount   BUY: the money to spend, e.g. "500.00", at least 1.00
     * @param quantity SELL: the units to sell, e.g. "1.5", up to 6 fraction digits
     */
    record OrderRequest(
            @NotNull UUID portfolioId,
            @NotNull @Pattern(regexp = ISIN, message = "must be an ISIN") String isin,
            @NotNull Order.Side side,
            @Pattern(regexp = POSITIVE_AMOUNT, message = "must be a positive amount with at most two fraction digits")
            String amount,
            @Pattern(regexp = POSITIVE_QUANTITY,
                    message = "must be a positive quantity with at most six fraction digits")
            String quantity) {

        @AssertTrue(message = "a BUY names an amount of at least 1.00 and no quantity; a SELL a quantity and no amount")
        boolean isSized() {
            if (side == Order.Side.BUY) {
                return quantity == null && amount != null && amount.matches(POSITIVE_AMOUNT)
                        && new BigDecimal(amount).compareTo(Order.MINIMUM_BUY.amount()) >= 0;
            }
            return side != Order.Side.SELL || (amount == null && quantity != null);
        }
    }

    /** {@code status}: RECEIVED until the opening has been applied, then OPEN. */
    record PortfolioResponse(UUID portfolioId, String status, UUID customerId, UUID referenceAccountId,
            Instant openedAt, List<HoldingResponse> holdings, List<UnpaidPayoutResponse> unpaidPayouts) {

        static PortfolioResponse received(Command.OpenPortfolio open) {
            return new PortfolioResponse(open.portfolioId(), "RECEIVED", open.customerId(), open.referenceAccountId(),
                    null, List.of(), List.of());
        }

        static PortfolioResponse from(InvestmentService.PortfolioView view) {
            var portfolio = view.portfolio();
            return new PortfolioResponse(portfolio.id(), "OPEN", portfolio.customerId(),
                    portfolio.referenceAccountId(), portfolio.openedAt(),
                    view.holdings().stream().map(HoldingResponse::from).toList(),
                    view.unpaidPayouts().stream().map(UnpaidPayoutResponse::from).toList());
        }
    }

    /**
     * @param costBasis what the units held cost, first in, first out
     * @param lastPrice of the last fill; there is no market data yet (ADR 0026)
     */
    record HoldingResponse(String isin, String quantity, String reservedForSale, String costBasis, String lastPrice) {

        static HoldingResponse from(Holding holding) {
            return new HoldingResponse(holding.isin(), holding.quantity().toString(), holding.reserved().toString(),
                    holding.costBasis().toPlainString(), holding.lastPrice().map(Price::toString).orElse(null));
        }
    }

    /** Money the current account refused; retried when it is unblocked and once a day. */
    record UnpaidPayoutResponse(UUID payoutId, String orderId, String reason, String amount, Instant unpaidSince,
            int attempts) {

        static UnpaidPayoutResponse from(Payout payout) {
            return new UnpaidPayoutResponse(payout.id(), payout.orderId().orElse(null), payout.reason().name(),
                    payout.amount().toString(), payout.unpaidSince().orElse(null), payout.attempt());
        }
    }

    /**
     * {@code status}: RECEIVED until the order has been applied; then FUNDING, PLACED, PARTIALLY_FILLED,
     * FILLED, EXPIRED, REJECTED or FUNDING_FAILED. {@code settlement}: PENDING, SETTLED or UNPAID.
     */
    record OrderResponse(String orderId, UUID portfolioId, String isin, String side, String amount, String quantity,
            String status, String settlement, LocalDate tradingDay, String filledQuantity, String filledAmount,
            String rejectionReason, Instant acceptedAt, List<FillResponse> fills) {

        static OrderResponse received(Order.Request request) {
            return new OrderResponse(request.orderId(), request.portfolioId(), request.isin(), request.side().name(),
                    text(request.amount()), text(request.quantity()), "RECEIVED", null, null, null, null, null,
                    request.acceptedAt(), List.of());
        }

        static OrderResponse from(Order order, List<Fill> fills) {
            var request = order.request();
            return new OrderResponse(order.id(), request.portfolioId(), request.isin(), request.side().name(),
                    text(request.amount()), text(request.quantity()), order.status().name(),
                    order.settlement().name(), order.tradingDay().orElse(null), order.filledQuantity().toString(),
                    order.filledAmount().toPlainString(), order.rejectionReason().orElse(null), request.acceptedAt(),
                    fills.stream().map(FillResponse::from).toList());
        }
    }

    /** @param realizedGain sells only: proceeds minus the FIFO cost of the units sold */
    record FillResponse(String execId, String quantity, String price, String amount, String realizedGain,
            Instant tradedAt) {

        static FillResponse from(Fill fill) {
            return new FillResponse(fill.execId(), fill.quantity().toString(), fill.price().toString(),
                    fill.amount().toPlainString(), fill.gain().map(BigDecimal::toPlainString).orElse(null),
                    fill.tradedAt());
        }
    }

    /** @param amount signed: negative for money leaving the portfolio */
    record TransactionResponse(long entryNo, String type, String amount, String balanceAfter, String reference,
            String orderId, String isin, String quantity, String price, String realizedGain, Instant bookedAt) {

        static TransactionResponse from(CashEntry entry) {
            var trade = entry.tradeDetails();
            return new TransactionResponse(entry.entryNo(), entry.type().name(), entry.amount().toPlainString(),
                    entry.balanceAfter().toPlainString(), entry.reference(), entry.orderId(),
                    trade.map(CashEntry.Trade::isin).orElse(null),
                    trade.map(t -> t.quantity().toString()).orElse(null),
                    trade.map(t -> t.price().toString()).orElse(null),
                    trade.map(CashEntry.Trade::realizedGain).map(BigDecimal::toPlainString).orElse(null),
                    entry.bookedAt());
        }
    }

    /** @param nextCursor pass as {@code cursor} for the next page; null on the last */
    record TransactionsPage(List<TransactionResponse> transactions, String nextCursor) {
    }

    record InstrumentResponse(String isin, String name, String type) {

        static InstrumentResponse from(Instrument instrument) {
            return new InstrumentResponse(instrument.isin(), instrument.name(), instrument.type().name());
        }
    }

    private final InvestmentService investments;
    private final Clock clock;

    InvestmentController(InvestmentService investments, Clock clock) {
        this.investments = investments;
        this.clock = clock;
    }

    @GetMapping("/instruments")
    List<InstrumentResponse> instruments() {
        return investments.instrumentsOnSale(clock.instant()).stream().map(InstrumentResponse::from).toList();
    }

    @PostMapping("/portfolios")
    ResponseEntity<PortfolioResponse> open(
            @RequestHeader(IdempotencyKey.HEADER) @Pattern(regexp = IdempotencyKey.PATTERN,
                    message = IdempotencyKey.MESSAGE) String idempotencyKey,
            @Valid @RequestBody OpenRequest body) {
        var portfolioId = IdempotencyKey.uuid("portfolio", idempotencyKey);
        var open = new Command.OpenPortfolio(portfolioId, body.customerId(), body.referenceAccountId(),
                clock.instant());
        var response = investments.submit(open).map(portfolio -> PortfolioResponse.from(
                investments.portfolio(portfolio.id()))).orElseGet(() -> PortfolioResponse.received(open));
        return ResponseEntity.accepted().location(URI.create("/portfolios/" + portfolioId)).body(response);
    }

    @GetMapping("/portfolios/{portfolioId}")
    PortfolioResponse portfolio(@PathVariable UUID portfolioId) {
        return PortfolioResponse.from(investments.portfolio(portfolioId));
    }

    /** The portfolio's money ledger, newest first: funding, fills, payouts. */
    @GetMapping("/portfolios/{portfolioId}/transactions")
    TransactionsPage transactions(@PathVariable UUID portfolioId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) @Pattern(regexp = "\\d{1,18}") String cursor) {
        var entries = investments.transactions(portfolioId, cursor == null ? null : Long.valueOf(cursor), limit);
        var next = entries.size() == limit && entries.getLast().entryNo() > 1
                ? String.valueOf(entries.getLast().entryNo())
                : null;
        return new TransactionsPage(entries.stream().map(TransactionResponse::from).toList(), next);
    }

    @PostMapping("/orders")
    ResponseEntity<OrderResponse> place(
            @RequestHeader(IdempotencyKey.HEADER) @Pattern(regexp = IdempotencyKey.PATTERN,
                    message = IdempotencyKey.MESSAGE) String idempotencyKey,
            @Valid @RequestBody OrderRequest body) {
        var orderId = "ord-" + IdempotencyKey.uuid("investment-order", idempotencyKey);
        var request = new Order.Request(orderId, body.portfolioId(), body.isin(), body.side(),
                body.side() == Order.Side.BUY ? Money.of(body.amount()) : null,
                body.side() == Order.Side.SELL ? Quantity.of(body.quantity()) : null, clock.instant());
        var response = investments.submit(new Command.PlaceOrder(request))
                .map(order -> OrderResponse.from(order, investments.order(order.id()).fills()))
                .orElseGet(() -> OrderResponse.received(request));
        return ResponseEntity.accepted().location(URI.create("/orders/" + orderId)).body(response);
    }

    @GetMapping("/orders/{orderId}")
    OrderResponse order(@PathVariable @Pattern(regexp = ORDER_ID) String orderId) {
        var view = investments.order(orderId);
        return OrderResponse.from(view.order(), view.fills());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
