package com.example.savings.adapter.persistence;

import com.example.savings.domain.Account;
import com.example.savings.domain.Interest;
import com.example.savings.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Table mapping of {@link Account}. Never leaves this package; the domain object is built from it. */
@Entity
@Table(name = "account")
class AccountEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal annualRate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, precision = 19, scale = Interest.ACCRUAL_SCALE)
    private BigDecimal accruedInterest;

    @Column(nullable = false)
    private LocalDate lastAccrualDate;

    @Column(nullable = false, updatable = false)
    private Instant openedAt;

    @Column(nullable = false)
    private long lastEntryNo;

    @Column(nullable = false)
    private Instant lastBookedAt;

    /** Incremented by Hibernate on every update; a stale value makes the update fail. */
    @Version
    private Long version;

    protected AccountEntity() {
    }

    static AccountEntity from(Account.State state) {
        var entity = new AccountEntity();
        entity.id = state.id();
        entity.customerId = state.customerId();
        entity.annualRate = state.annualRate();
        entity.openedAt = state.openedAt();
        entity.version = state.version();
        entity.apply(state);
        return entity;
    }

    /** Copies what an account can change; the version is left to Hibernate. */
    void apply(Account.State state) {
        balance = state.balance().amount();
        accruedInterest = state.accruedInterest();
        lastAccrualDate = state.lastAccrualDate();
        lastEntryNo = state.lastEntryNo();
        lastBookedAt = state.lastBookedAt();
    }

    Account.State toState() {
        return new Account.State(id, customerId, annualRate, new Money(balance), accruedInterest, lastAccrualDate,
                openedAt, lastEntryNo, lastBookedAt, version);
    }

    UUID id() {
        return id;
    }

    Long version() {
        return version;
    }
}
