package com.example.savings.adapter.persistence;

import com.example.savings.application.UnknownAccountException;
import com.example.savings.application.port.Accounts;
import com.example.savings.domain.Account;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link Accounts} on JPA: maps between {@link Account} and {@link AccountEntity}. */
@Component
class JpaAccounts implements Accounts {

    private final AccountJpaRepository repository;

    JpaAccounts(AccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Account account) {
        repository.save(AccountEntity.from(account.state()));
    }

    @Override
    public Account get(UUID id) {
        return repository.findById(id)
                .map(entity -> Account.restore(entity.toState()))
                .orElseThrow(() -> new UnknownAccountException(id));
    }

    /**
     * Copies the account's state onto the managed entity; Hibernate writes it on commit, with
     * {@code where version = ?}. Refuses up front if the account was read at another version than
     * the stored one, so a change can never be applied on top of state it didn't see.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Account account) {
        var state = account.state();
        var entity = repository.findById(state.id()).orElseThrow(() -> new UnknownAccountException(state.id()));
        if (!Objects.equals(entity.version(), state.version())) {
            throw new OptimisticLockingFailureException("Account " + state.id() + " was changed concurrently");
        }
        entity.apply(state);
    }

    @Override
    public List<UUID> idsDueForAccrual(LocalDate through, UUID after, int limit) {
        return repository.findIdsDueForAccrual(through, after, Limit.of(limit));
    }

    @Override
    public Optional<LocalDate> oldestAccrualDate() {
        return repository.findOldestAccrualDate();
    }
}
