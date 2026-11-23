package com.example.transfers.adapter.web;

import com.example.transfers.application.TransferService;
import com.example.transfers.domain.Money;
import com.example.transfers.domain.Transfer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfers between a customer's own accounts. {@code POST} only accepts the request into the command
 * log and answers {@code 202} (ADR 0019); the outcome is read with {@code GET} or from
 * {@code payments.transfer-events.v1}. The transfer id is derived from the {@link IdempotencyKey}, so a
 * retry with the same key is safe, and a client can never pick an id another service uses, such as
 * savings' {@code savings-order-<orderId>} (ADR 0020, 0024).
 */
@RestController
@RequestMapping("/transfers")
class TransferController {

    static final String TRANSFER_ID = "[A-Za-z0-9._:-]{1,100}";

    /** @param amount decimal string, e.g. "100.00" */
    record TransferRequestBody(
            @NotNull UUID customerId,
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @Pattern(regexp = "(?!0+(\\.0{1,2})?$)\\d{1,17}(\\.\\d{1,2})?",
                    message = "must be a positive amount with at most two fraction digits") String amount) {
    }

    /**
     * {@code status}: RECEIVED until the request has been applied, then AWAITING_ACCOUNTS, DEBITING,
     * CREDITING, RETURNING, COMPLETED or FAILED.
     */
    record TransferResponse(String transferId, String status, UUID customerId, UUID fromAccountId, UUID toAccountId,
            String amount, Instant requestedAt, String route, String failureReason, String failureDetail) {

        static TransferResponse received(Transfer.Request request) {
            return new TransferResponse(request.transferId(), "RECEIVED", request.customerId(),
                    request.fromAccountId(), request.toAccountId(), request.amount().toString(),
                    request.requestedAt(), null, null, null);
        }

        static TransferResponse from(Transfer transfer) {
            var request = transfer.request();
            return new TransferResponse(request.transferId(), transfer.status().name(), request.customerId(),
                    request.fromAccountId(), request.toAccountId(), request.amount().toString(),
                    request.requestedAt(), transfer.route().map(Enum::name).orElse(null),
                    transfer.failureReason().map(Enum::name).orElse(null), transfer.failureDetail().orElse(null));
        }
    }

    private final TransferService transfers;
    private final Clock clock;

    TransferController(TransferService transfers, Clock clock) {
        this.transfers = transfers;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<TransferResponse> request(
            @RequestHeader(IdempotencyKey.HEADER) @Pattern(regexp = IdempotencyKey.PATTERN,
                    message = IdempotencyKey.MESSAGE) String idempotencyKey,
            @Valid @RequestBody TransferRequestBody body) {
        var transferId = "tr-" + IdempotencyKey.uuid("transfer", idempotencyKey);
        var request = new Transfer.Request(transferId, body.customerId(), body.fromAccountId(), body.toAccountId(),
                Money.of(body.amount()), clock.instant());
        var response = transfers.submit(request).map(TransferResponse::from)
                .orElseGet(() -> TransferResponse.received(request));
        return ResponseEntity.accepted().location(URI.create("/transfers/" + transferId)).body(response);
    }

    @GetMapping("/{transferId}")
    TransferResponse get(@PathVariable @Pattern(regexp = TRANSFER_ID) String transferId) {
        return TransferResponse.from(transfers.get(transferId));
    }
}
