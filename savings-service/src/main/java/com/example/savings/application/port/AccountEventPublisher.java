package com.example.savings.application.port;

import com.example.savings.domain.AccountEvent;

/**
 * Publishes account events. Must be called inside the business transaction: the event becomes
 * visible to other services if and only if that transaction commits.
 */
public interface AccountEventPublisher {

    void publish(AccountEvent event);
}
