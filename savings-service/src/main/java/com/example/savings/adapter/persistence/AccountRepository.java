package com.example.savings.adapter.persistence;

import com.example.savings.application.UnknownAccountException;
import com.example.savings.application.port.Accounts;
import com.example.savings.domain.Account;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements the {@link Accounts} port directly; default methods adapt the vocabulary. */
public interface AccountRepository extends JpaRepository<Account, UUID>, Accounts {

    @Override
    default void add(Account account) {
        save(account);
    }

    @Override
    default Account get(UUID id) {
        return findById(id).orElseThrow(() -> new UnknownAccountException(id));
    }

    @Override
    default List<UUID> idsDueForAccrual(LocalDate through, UUID after, int limit) {
        return findIdsDueForAccrual(through, after, Limit.of(limit));
    }

    @Override
    default Optional<LocalDate> oldestAccrualDate() {
        return findOldestAccrualDate();
    }

    @Query("select min(a.lastAccrualDate) from Account a")
    Optional<LocalDate> findOldestAccrualDate();

    @Query("select a.id from Account a where a.lastAccrualDate < :through and a.id > :after order by a.id")
    List<UUID> findIdsDueForAccrual(LocalDate through, UUID after, Limit limit);
}
