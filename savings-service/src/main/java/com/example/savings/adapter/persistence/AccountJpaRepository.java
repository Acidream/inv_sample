package com.example.savings.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

    @Query("select a.id from AccountEntity a where a.lastAccrualDate < :through and a.id > :after order by a.id")
    List<UUID> findIdsDueForAccrual(LocalDate through, UUID after, Limit limit);

    @Query("select min(a.lastAccrualDate) from AccountEntity a")
    Optional<LocalDate> findOldestAccrualDate();
}
