package com.example.investments.application.port;

import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.Posting;
import com.example.investments.domain.TransferAnswer;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything the investment service tells others. Must be called inside the transaction that made
 * the change: each message is sent if and only if that transaction commits (the outbox, ADR 0006).
 */
public interface Outbound {

    /** {@code PortfolioOpened}, from which the transfer service learns the portfolio (ADR 0028). */
    void portfolioOpened(Portfolio portfolio);

    /** The answer to an instruction of the transfer service. */
    void answered(TransferAnswer answer);

    /** Asks the transfer service to move money between the portfolio and its reference account. */
    void requestTransfer(String transferId, UUID customerId, UUID fromAccountId, UUID toAccountId,
            Money amount, Instant at);

    /** Places the order with the broker, through the gateway (ADR 0027). */
    void placeWithBroker(Order order, Instant at);

    void posting(Posting posting, Instant at);
}
