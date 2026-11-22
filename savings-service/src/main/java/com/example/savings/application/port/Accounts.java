package com.example.savings.application.port;

import com.example.savings.domain.Account;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account storage. A changed account must be passed to {@link #update} in the transaction that
 * changed it; nothing is saved implicitly.
 */
public interface Accounts {

    /** Stores a new account. */
    void add(Account account);

    /** @throws com.example.savings.application.UnknownAccountException if there is no such account */
    Account get(UUID id);

    /**
     * Stores the changes made to an account read with {@link #get}.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException if it was changed concurrently
     */
    void update(Account account);

    /** Keyset-paginated ids of accounts whose interest is not yet accrued through {@code through}. */
    List<UUID> idsDueForAccrual(LocalDate through, UUID after, int limit);

    /** The earliest {@code lastAccrualDate} of any account; empty if there are no accounts. */
    Optional<LocalDate> oldestAccrualDate();
}
