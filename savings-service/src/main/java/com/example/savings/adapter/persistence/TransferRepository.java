package com.example.savings.adapter.persistence;

import com.example.savings.application.port.Transfers;
import com.example.savings.domain.Transfer;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Bare {@link Repository}: transfers are added and looked up, never deleted. The {@code @Version}
 * field (null for a new entity) makes {@code save} a plain insert; a concurrent insert of the same
 * transfer id fails on the primary key and the message is retried.
 */
public interface TransferRepository extends Repository<Transfer, String>, Transfers {

    Optional<Transfer> findById(String transferId);

    Transfer save(Transfer transfer);

    @Override
    default Optional<Transfer> find(String transferId) {
        return findById(transferId);
    }

    @Override
    default void add(Transfer transfer) {
        save(transfer);
    }
}
