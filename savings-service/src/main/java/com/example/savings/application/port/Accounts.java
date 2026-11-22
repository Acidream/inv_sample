package com.example.savings.application.port;

import com.example.savings.domain.Account;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account storage. Loaded aggregates are tracked by the persistence context, so changes made
 * inside a transaction are written on commit without an explicit save.
 */
public interface Accounts {

    void add(Account account);

    /** @throws com.example.savings.application.UnknownAccountException if there is no such account */
    Account get(UUID id);

    /** Keyset-paginated ids of accounts whose interest is not yet accrued through {@code through}. */
    List<UUID> idsDueForAccrual(LocalDate through, UUID after, int limit);

    /** The earliest {@code lastAccrualDate} of any account; empty if there are no accounts. */
    Optional<LocalDate> oldestAccrualDate();
}
