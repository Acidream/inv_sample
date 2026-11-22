package com.example.savings.adapter.web;

import com.example.savings.adapter.web.AccountDtos.AccountResponse;
import com.example.savings.adapter.web.AccountDtos.OpenAccountRequest;
import com.example.savings.adapter.web.AccountDtos.StatementResponse;
import com.example.savings.application.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account opening and queries. Money movements do not come through REST: they arrive as
 * instructions from Payments on Kafka.
 */
@RestController
@RequestMapping("/accounts")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * A client that may retry (e.g. after a timeout) sends an {@code Idempotency-Key}: the same request
     * with the same key gets the same account back, with the same {@code 201}, instead of a second
     * account. The same key with a different body is a {@code 422}.
     */
    @PostMapping
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(min = 1, max = 100)
            String idempotencyKey) {
        var account = accounts.open(request.customerId(), request.annualRate(), idempotencyKey).account();
        return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accounts.get(id));
    }

    @GetMapping("/{id}/transactions")
    StatementResponse transactions(@PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        var statement = accounts.statement(id, StatementCursorCodec.decode(cursor), limit);
        return StatementResponse.from(statement);
    }
}
