package com.example.currentaccount.adapter.web;

import com.example.currentaccount.application.Command;
import com.example.currentaccount.application.CurrentAccountService;
import com.example.currentaccount.domain.Booking;
import com.example.currentaccount.domain.CurrentAccount;
import com.example.currentaccount.domain.IncomingPayment;
import com.example.currentaccount.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current accounts (ADR 0021). Every change is a {@code PUT} or {@code DELETE} that only accepts a
 * command into the command log and answers {@code 202} (ADR 0019); its effect is read with
 * {@code GET}. Ids are chosen by the client, so a retry is safe.
 *
 * <p>Money moves between a customer's accounts through the transfer service, not here.
 */
@RestController
@RequestMapping("/current-accounts")
class CurrentAccountController {

    static final String PAYMENT_ID = "[A-Za-z0-9._:-]{1,100}";
    private static final String AMOUNT = "\\d{1,17}(\\.\\d{1,2})?";
    private static final String POSITIVE_AMOUNT = "(?!0+(\\.0{1,2})?$)" + AMOUNT;

    /** @param overdraftLimit decimal string, e.g. "500.00"; "0.00" for none */
    record OpenRequest(
            @NotNull UUID customerId,
            @NotNull @Pattern(regexp = AMOUNT, message = "must be an amount with at most two fraction digits")
            String overdraftLimit) {
    }

    /** @param amount decimal string, e.g. "2500.00" */
    record PaymentRequest(
            @NotNull @Pattern(regexp = POSITIVE_AMOUNT,
                    message = "must be a positive amount with at most two fraction digits") String amount) {
    }

    record BlockRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /**
     * {@code status}: RECEIVED until the opening has been applied, then OPEN or BLOCKED.
     *
     * @param available what can still be spent: balance plus unused overdraft; zero while blocked
     */
    record AccountResponse(UUID accountId, String status, UUID customerId, String balance, String overdraftLimit,
            String available, String blockReason, Instant openedAt) {

        static AccountResponse received(Command.Open open) {
            return new AccountResponse(open.accountId(), "RECEIVED", open.customerId(), null,
                    plain(open.overdraftLimit()), null, null, null);
        }

        static AccountResponse from(CurrentAccount account) {
            return new AccountResponse(account.id(), account.status().name(), account.customerId(),
                    plain(account.balance()), plain(account.overdraftLimit()), plain(account.available()),
                    account.blockReason().orElse(null), account.openedAt());
        }
    }

    /** {@code status}: RECEIVED until the payment has been applied, then BOOKED or REJECTED. */
    record PaymentResponse(String paymentId, UUID accountId, String status, String amount, String rejectionReason,
            Instant receivedAt) {

        static PaymentResponse received(Command.ReceivePayment payment) {
            return new PaymentResponse(payment.paymentId(), payment.accountId(), "RECEIVED",
                    payment.amount().toString(), null, payment.acceptedAt());
        }

        static PaymentResponse from(IncomingPayment payment) {
            return new PaymentResponse(payment.paymentId(), payment.accountId(), payment.status().name(),
                    payment.amount().toString(), payment.rejection().orElse(null), payment.receivedAt());
        }
    }

    /** @param amount signed: negative for money leaving the account */
    record BookingResponse(String reference, String type, String amount, String balanceAfter, Instant bookedAt) {

        static BookingResponse from(Booking booking) {
            return new BookingResponse(booking.reference(), booking.type().name(), plain(booking.amount()),
                    plain(booking.balanceAfter()), booking.bookedAt());
        }
    }

    private final CurrentAccountService accounts;
    private final Clock clock;

    CurrentAccountController(CurrentAccountService accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @PutMapping("/{accountId}")
    ResponseEntity<AccountResponse> open(@PathVariable UUID accountId, @Valid @RequestBody OpenRequest body) {
        var open = new Command.Open(accountId, body.customerId(),
                CurrentAccount.overdraftLimit(new BigDecimal(body.overdraftLimit())), clock.instant());
        var response = accounts.submit(open).map(AccountResponse::from).orElseGet(() -> AccountResponse.received(open));
        return ResponseEntity.accepted().location(URI.create("/current-accounts/" + accountId)).body(response);
    }

    @GetMapping("/{accountId}")
    AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(accounts.get(accountId));
    }

    @GetMapping("/{accountId}/bookings")
    List<BookingResponse> bookings(@PathVariable UUID accountId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return accounts.bookings(accountId, limit).stream().map(BookingResponse::from).toList();
    }

    /** Money arriving from outside the bank, e.g. a salary. */
    @PutMapping("/{accountId}/incoming-payments/{paymentId}")
    ResponseEntity<PaymentResponse> receive(@PathVariable UUID accountId,
            @PathVariable @Pattern(regexp = PAYMENT_ID) String paymentId, @Valid @RequestBody PaymentRequest body) {
        var payment = new Command.ReceivePayment(accountId, paymentId, Money.of(body.amount()), clock.instant());
        var response = accounts.submit(payment).map(PaymentResponse::from)
                .orElseGet(() -> PaymentResponse.received(payment));
        return ResponseEntity.accepted()
                .location(URI.create("/current-accounts/" + accountId + "/incoming-payments/" + paymentId))
                .body(response);
    }

    @GetMapping("/{accountId}/incoming-payments/{paymentId}")
    PaymentResponse payment(@PathVariable UUID accountId,
            @PathVariable @Pattern(regexp = PAYMENT_ID) String paymentId) {
        return PaymentResponse.from(accounts.payment(accountId, paymentId));
    }

    /** A blocked account refuses transfers both ways. */
    @PutMapping("/{accountId}/block")
    ResponseEntity<Void> block(@PathVariable UUID accountId, @Valid @RequestBody BlockRequest body) {
        accounts.submit(new Command.Block(accountId, body.reason(), clock.instant()));
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{accountId}/block")
    ResponseEntity<Void> unblock(@PathVariable UUID accountId) {
        accounts.submit(new Command.Unblock(accountId, clock.instant()));
        return ResponseEntity.accepted().build();
    }

    private static String plain(BigDecimal amount) {
        return amount.toPlainString();
    }
}
