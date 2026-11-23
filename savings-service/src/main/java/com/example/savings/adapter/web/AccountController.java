package com.example.savings.adapter.web;

import com.example.savings.adapter.web.AccountDtos.AccountResponse;
import com.example.savings.adapter.web.AccountDtos.StatementResponse;
import com.example.savings.application.AccountService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account queries. Accounts are opened by orders ({@code PUT /orders/{id}}, ADR 0020); money moves
 * through the transfer service. Nothing here changes state (ADR 0019).
 */
@RestController
@RequestMapping("/accounts")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
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
