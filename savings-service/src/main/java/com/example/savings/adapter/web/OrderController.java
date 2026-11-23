package com.example.savings.adapter.web;

import com.example.savings.application.order.OrderService;
import com.example.savings.domain.Money;
import com.example.savings.domain.order.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders to open and fund a savings account (ADR 0020). {@code PUT} only accepts the order into the
 * command log and answers {@code 202}; the order is applied when savings consumes it (ADR 0019), and
 * its outcome is read with {@code GET}. The client chooses the order id, so a retry is safe.
 */
@RestController
@RequestMapping("/orders")
class OrderController {

    /**
     * @param productVersion the version whose terms the customer accepted (see {@code GET /products})
     * @param amount         decimal string, e.g. "250.00"
     * @param fromAccountId  the customer's current account the money comes from
     */
    record OpenAndFundRequest(
            @NotNull UUID customerId,
            @NotBlank @Size(max = 40) String productCode,
            @NotNull @Positive Integer productVersion,
            @NotNull @Pattern(regexp = "(?!0+(\\.0{1,2})?$)\\d{1,17}(\\.\\d{1,2})?",
                    message = "must be a positive amount with at most two fraction digits") String amount,
            @NotNull UUID fromAccountId) {
    }

    /** {@code status}: RECEIVED until the order has been applied, then REJECTED, FUNDING, FUNDED or FUNDING_FAILED. */
    record OrderResponse(UUID orderId, String status, UUID customerId, String productCode, int productVersion,
            String amount, UUID fromAccountId, Instant placedAt, UUID accountId, String rejectionReason,
            String failureReason) {

        static OrderResponse received(Order.Request request) {
            return new OrderResponse(request.orderId(), "RECEIVED", request.customerId(), request.productCode(),
                    request.productVersion(), request.amount().toString(), request.fromAccountId(), request.placedAt(),
                    null, null, null);
        }

        static OrderResponse from(Order order) {
            var request = order.request();
            return new OrderResponse(request.orderId(), order.status().name(), request.customerId(),
                    request.productCode(), request.productVersion(), request.amount().toString(),
                    request.fromAccountId(), request.placedAt(), order.accountId().orElse(null),
                    order.rejectionReason().map(Enum::name).orElse(null), order.failureReason().orElse(null));
        }
    }

    private final OrderService orders;
    private final Clock clock;

    OrderController(OrderService orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    @PutMapping("/{orderId}")
    ResponseEntity<OrderResponse> place(@PathVariable UUID orderId, @Valid @RequestBody OpenAndFundRequest body) {
        var request = new Order.Request(orderId, body.customerId(), body.productCode(), body.productVersion(),
                Money.of(body.amount()), body.fromAccountId(), clock.instant());
        var response = orders.submit(request).map(OrderResponse::from).orElseGet(() -> OrderResponse.received(request));
        return ResponseEntity.accepted().location(URI.create("/orders/" + orderId)).body(response);
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable UUID orderId) {
        return OrderResponse.from(orders.get(orderId));
    }
}
