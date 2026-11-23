package com.example.savings.application.order;

import com.example.savings.application.SavingsPolicy;
import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.CommandLog;
import com.example.savings.application.port.Orders;
import com.example.savings.application.port.ProcessedEvents;
import com.example.savings.application.port.Products;
import com.example.savings.application.port.TransferRequests;
import com.example.savings.application.port.Transfers;
import com.example.savings.application.port.TransferRequests.TransferRequest;
import com.example.savings.domain.Account;
import com.example.savings.domain.AccountEvent.AccountOpened;
import com.example.savings.domain.order.Order;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening and funding a savings account (ADR 0020). Customers' orders are accepted into the command
 * log ({@link #submit}) and applied when consumed from it ({@link #place}); the funding transfer's
 * outcome comes back from the transfer service.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final Orders orders;
    private final Products products;
    private final Accounts accounts;
    private final AccountEventPublisher events;
    private final TransferRequests transferRequests;
    private final Transfers transfers;
    private final ProcessedEvents processedEvents;
    private final CommandLog commandLog;
    private final SavingsPolicy policy;

    public OrderService(Orders orders, Products products, Accounts accounts, AccountEventPublisher events,
            TransferRequests transferRequests, Transfers transfers, ProcessedEvents processedEvents,
            CommandLog commandLog, SavingsPolicy policy) {
        this.orders = orders;
        this.products = products;
        this.accounts = accounts;
        this.events = events;
        this.transferRequests = transferRequests;
        this.transfers = transfers;
        this.processedEvents = processedEvents;
        this.commandLog = commandLog;
        this.policy = policy;
    }

    /**
     * Accepts an order into the command log; changes no state (ADR 0019). An order id that was already
     * applied returns that order: the same order again is a retry, a different one is refused.
     *
     * <p>Deliberately not transactional: the lookup is one statement, and the Kafka send may wait
     * seconds for its acknowledgement. Inside a transaction it would hold a database connection all
     * that time, and a slow Kafka would take every connection the consumers and the outbox need.
     *
     * @return the order as applied, or empty if it was just appended and not yet applied
     * @throws OrderIdInUseException if the id belongs to a different order
     */
    public Optional<Order> submit(Order.Request request) {
        var applied = orders.find(request.orderId());
        if (applied.isPresent()) {
            if (!applied.get().request().sameOrderAs(request)) {
                throw new OrderIdInUseException(request.orderId());
            }
            return applied;
        }
        commandLog.openAndFund(UUID.randomUUID().toString(), request);
        return Optional.empty();
    }

    /**
     * Applies an order from the command log, in one transaction: decide it as of the time it was
     * placed, open the account on the accepted version, announce it and ask for the money.
     */
    @Transactional
    public void place(String commandId, Order.Request request) {
        if (!processedEvents.markProcessed("command:" + commandId)) {
            log.info("Skipping already applied command {}", commandId);
            return;
        }
        var earlier = orders.find(request.orderId());
        if (earlier.isPresent()) {
            if (!earlier.get().request().sameOrderAs(request)) {
                log.warn("Ignoring command {}: order {} was already placed with different details", commandId,
                        request.orderId());
            }
            return;
        }
        var product = products.onSale(request.productCode(), request.placedAt());
        var order = Order.place(request, product);
        if (order.status() == Order.Status.FUNDING) {
            var accountId = order.accountId().orElseThrow();
            var account = Account.open(accountId, request.customerId(), product.orElseThrow(), request.placedAt(),
                    policy.businessZone());
            accounts.add(account);
            events.publish(new AccountOpened(accountId, request.customerId(), account.product(), request.orderId()));
            transferRequests.request(new TransferRequest(order.transferId().orElseThrow(), request.customerId(),
                    request.fromAccountId(), accountId, request.amount(), request.placedAt()));
        } else {
            log.info("Order {} rejected: {}", request.orderId(), order.rejectionReason().orElseThrow());
        }
        orders.add(order);
    }

    /**
     * The funding transfer completed. Transfers savings didn't ask for are not its business. The order
     * checks the report against the deposit savings booked (see {@link Order#funded}).
     */
    @Transactional
    public void transferCompleted(String eventId, String transferId) {
        if (!processedEvents.markProcessed("transfer-event:" + eventId)) {
            return;
        }
        orders.findByTransferId(transferId).ifPresent(order -> {
            order.funded(transfers.find(transferId));
            orders.update(order);
        });
    }

    @Transactional
    public void transferFailed(String eventId, String transferId, String reason) {
        if (!processedEvents.markProcessed("transfer-event:" + eventId)) {
            return;
        }
        orders.findByTransferId(transferId).ifPresent(order -> {
            order.fundingFailed(reason, transfers.find(transferId));
            orders.update(order);
        });
    }

    @Transactional(readOnly = true)
    public Order get(UUID orderId) {
        return orders.find(orderId).orElseThrow(() -> new UnknownOrderException(orderId));
    }
}
