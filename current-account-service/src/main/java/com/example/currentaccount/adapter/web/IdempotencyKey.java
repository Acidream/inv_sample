package com.example.currentaccount.adapter.web;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The {@code Idempotency-Key} header of the {@code POST}s that create something (ADR 0025). The
 * client sends a key of its choosing, the same on every retry; the id of what is created is derived
 * from it, so a retry, a request that raced its own retry and a replay of the command log all land on
 * the same account or payment. A key is required: without one, a lost response could never be retried safely.
 */
final class IdempotencyKey {

    static final String HEADER = "Idempotency-Key";
    /** Printable ASCII, no spaces; only its hash is stored, so length and characters don't reach the database. */
    static final String PATTERN = "[!-~]{1,100}";
    static final String MESSAGE = "must be 1 to 100 printable ASCII characters without spaces";

    private IdempotencyKey() {
    }

    /**
     * Name-based (version 3) UUID of the key within a scope, so the same key used for two kinds of
     * resource names two different ones.
     */
    static UUID uuid(String scope, String key) {
        return UUID.nameUUIDFromBytes((scope + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
