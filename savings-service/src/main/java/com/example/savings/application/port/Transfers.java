package com.example.savings.application.port;

import com.example.savings.domain.Transfer;
import java.util.Optional;

/** Every transfer between the customer's current account and savings, by transfer id. */
public interface Transfers {

    Optional<Transfer> find(String transferId);

    /** Stores a new transfer. A transfer id that already exists fails. */
    void add(Transfer transfer);

    /**
     * Stores the changes made to a transfer read with {@link #find}.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException if it was changed concurrently
     */
    void update(Transfer transfer);
}
