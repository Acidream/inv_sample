package com.example.savings.adapter.persistence;

import com.example.savings.application.UnknownTransferException;
import com.example.savings.application.port.Transfers;
import com.example.savings.domain.Transfer;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Transfers} on JPA. A concurrent insert of the same transfer id fails on the primary key and
 * the message is retried, which then finds the transfer.
 */
@Component
class JpaTransfers implements Transfers {

    private final TransferJpaRepository repository;

    JpaTransfers(TransferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Transfer> find(String transferId) {
        return repository.findById(transferId).map(entity -> Transfer.restore(entity.toState()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Transfer transfer) {
        repository.save(TransferEntity.from(transfer.state()));
    }

    /** Same contract as {@link JpaAccounts#update}. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Transfer transfer) {
        var state = transfer.state();
        var entity = repository.findById(state.transferId())
                .orElseThrow(() -> new UnknownTransferException(state.transferId()));
        if (!Objects.equals(entity.version(), state.version())) {
            throw new OptimisticLockingFailureException("Transfer " + state.transferId() + " was changed concurrently");
        }
        entity.apply(state);
    }
}
