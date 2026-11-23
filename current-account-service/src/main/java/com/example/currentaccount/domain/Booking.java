package com.example.currentaccount.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the account's ledger. Append-only: a booking is never changed or deleted, a mistake is
 * corrected by another booking (ADR 0004).
 *
 * @param reference    the transfer id or the incoming payment id; unique per account and type
 * @param amount       signed: negative for money leaving the account
 * @param balanceAfter the balance once this booking is applied
 */
public record Booking(UUID accountId, String reference, Type type, BigDecimal amount, BigDecimal balanceAfter,
        Instant bookedAt) {

    public enum Type { TRANSFER_DEBIT, TRANSFER_CREDIT, INCOMING_PAYMENT }
}
