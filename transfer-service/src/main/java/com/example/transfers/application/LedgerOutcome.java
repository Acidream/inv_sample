package com.example.transfers.application;

import com.example.transfers.domain.KnownAccount.Ledger;

/**
 * A ledger's answer to an instruction, translated from its events.
 *
 * @param detail why a debit or credit was refused; empty otherwise
 */
public record LedgerOutcome(String messageId, Ledger ledger, String transferId, Kind kind, String detail) {

    public enum Kind { DEBITED, DEBIT_REJECTED, CREDITED, CREDIT_REFUSED, RETURNED }
}
