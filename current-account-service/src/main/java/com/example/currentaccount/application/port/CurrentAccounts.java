package com.example.currentaccount.application.port;

import com.example.currentaccount.domain.CurrentAccount;
import java.util.Optional;
import java.util.UUID;

public interface CurrentAccounts {

    Optional<CurrentAccount> find(UUID accountId);

    /**
     * Finds the account and locks it until the transaction ends: commands and instructions for one
     * account arrive on different topics and are consumed concurrently.
     */
    Optional<CurrentAccount> lock(UUID accountId);

    void add(CurrentAccount account);

    void update(CurrentAccount account);
}
