package com.example.savings.adapter.persistence;

import com.example.savings.domain.Money;
import com.example.savings.domain.Transfer;
import com.example.savings.domain.Transfer.Direction;
import com.example.savings.domain.Transfer.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Table mapping of {@link Transfer}. Never leaves this package. */
@Entity
@Table(name = "transfer")
class TransferEntity {

    @Id
    private String transferId;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant returnedAt;

    /** Null for a new transfer, which makes {@code save} a plain insert. */
    @Version
    private Long version;

    protected TransferEntity() {
    }

    static TransferEntity from(Transfer.State state) {
        var entity = new TransferEntity();
        entity.transferId = state.transferId();
        entity.accountId = state.accountId();
        entity.direction = state.direction();
        entity.amount = state.amount().amount();
        entity.createdAt = state.createdAt();
        entity.version = state.version();
        entity.apply(state);
        return entity;
    }

    /** Copies what a transfer can change; the version is left to Hibernate. */
    void apply(Transfer.State state) {
        status = state.status();
        returnedAt = state.returnedAt();
    }

    Transfer.State toState() {
        return new Transfer.State(transferId, accountId, direction, new Money(amount), status, createdAt, returnedAt,
                version);
    }

    Long version() {
        return version;
    }
}
