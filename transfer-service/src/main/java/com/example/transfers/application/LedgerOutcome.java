package com.example.transfers.application;

import com.example.transfers.domain.KnownAccount.Ledger;
import java.util.UUID;

/**
 * A ledger's answer to an instruction, translated from its events.
 *
 * @param accountId the account the ledger answered for
 * @param detail    why a debit or credit was refused; empty otherwise
 */
public record LedgerOutcome(String messageId, Ledger ledger, UUID accountId, String transferId, Kind kind,
        String detail) {

    public enum Kind { DEBITED, DEBIT_REJECTED, CREDITED, CREDIT_REFUSED, RETURNED }
}
