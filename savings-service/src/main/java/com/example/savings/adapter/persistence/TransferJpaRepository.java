package com.example.savings.adapter.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Bare {@link Repository}: transfers are added, looked up and updated, never deleted. */
interface TransferJpaRepository extends Repository<TransferEntity, String> {

    Optional<TransferEntity> findById(String transferId);

    TransferEntity save(TransferEntity transfer);
}
