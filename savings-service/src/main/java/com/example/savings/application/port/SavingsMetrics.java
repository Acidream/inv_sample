package com.example.savings.application.port;

import com.example.savings.domain.Money;

/** Business metrics, kept behind a port so use cases don't depend on a metrics library. */
public interface SavingsMetrics {

    void deposited(Money amount);

    void withdrawal(String outcome);

    void interestPaid(Money amount);

    void duplicateMessage();
}
