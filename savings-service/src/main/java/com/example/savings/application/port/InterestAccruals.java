package com.example.savings.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Audit trail of daily accruals. Unique per (account, day): accruing a day twice fails. */
public interface InterestAccruals {

    void record(UUID accountId, LocalDate day, BigDecimal amount);
}
