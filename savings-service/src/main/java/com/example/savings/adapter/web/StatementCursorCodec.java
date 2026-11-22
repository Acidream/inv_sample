package com.example.savings.adapter.web;

import com.example.savings.application.StatementCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Opaque cursor for clients: base64url of "bookedAt|entryNo". */
final class StatementCursorCodec {

    private StatementCursorCodec() {
    }

    static String encode(StatementCursor cursor) {
        var raw = cursor.bookedAt() + "|" + cursor.entryNo();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static StatementCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            return new StatementCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (RuntimeException e) {
            throw new InvalidCursorException(cursor);
        }
    }

    static class InvalidCursorException extends RuntimeException {

        InvalidCursorException(String cursor) {
            super("Invalid cursor: " + cursor);
        }
    }
}
