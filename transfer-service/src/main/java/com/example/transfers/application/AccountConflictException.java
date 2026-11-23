package com.example.transfers.application;

import com.example.transfers.domain.KnownAccount;

/**
 * An account id announced again with a different owner or ledger, e.g. a current account opened
 * under the id of an existing savings account. The directory keeps the first; the second needs a
 * person, so the event is dead-lettered rather than silently ignored (ADR 0023).
 */
public class AccountConflictException extends RuntimeException {

    public AccountConflictException(KnownAccount known, KnownAccount announced) {
        super("Account " + known.accountId() + " is known as " + known.ledger() + " of customer "
                + known.customerId() + " but was announced as " + announced.ledger() + " of customer "
                + announced.customerId());
    }
}
