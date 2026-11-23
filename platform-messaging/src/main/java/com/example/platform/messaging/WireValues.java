package com.example.platform.messaging;

import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Checks for values read from a message, for each service's anti-corruption layer (ADR 0005). A
 * value that fails throws {@link InvalidMessageException}, which {@link DeadLetters} parks on the
 * dead-letter topic, including values the database would refuse (too long, NUL characters, amounts
 * too large for {@code numeric(19, 2)}). Which values are valid for the business (currency, sign)
 * stays with the service.
 */
public final class WireValues {

    /** {@code numeric(19, 2)}: 17 digits before the point. */
    public static final int MAX_INTEGER_DIGITS = 17;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d+(\\.\\d{1,2})?");

    private WireValues() {
    }

    /** @return the value, if not blank */
    public static String required(String value, String field) {
        if (value.isBlank()) {
            throw new InvalidMessageException("Missing " + field);
        }
        return value;
    }

    /** @return the value, if not blank, at most {@code maxLength} characters and free of NUL */
    public static String id(String value, String field, int maxLength) {
        required(value, field);
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new InvalidMessageException(field + " longer than " + maxLength + " characters");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new InvalidMessageException(field + " contains a NUL character");
        }
        return value;
    }

    /** Free text for people: shortened to {@code maxLength} characters rather than refused. */
    public static String text(String value, int maxLength) {
        var clean = value.replace('\u0000', ' ');
        return clean.codePointCount(0, clean.length()) <= maxLength
                ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, maxLength));
    }

    public static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(required(value, field));
        } catch (InvalidMessageException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException(field + " is not a UUID: " + value, e);
        }
    }

    /** A non-negative amount written as a plain decimal with at most two fraction digits, e.g. "250.00". */
    public static BigDecimal plainAmount(String raw) {
        if (!PLAIN_DECIMAL.matcher(raw).matches()) {
            throw new InvalidMessageException("Amount must be a plain decimal with at most two fraction digits: "
                    + raw);
        }
        var amount = new BigDecimal(raw);
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidMessageException("Amount too large: " + raw);
        }
        return amount;
    }

    /** @param timestamp null if the message doesn't have it */
    public static Instant instant(Timestamp timestamp, String field) {
        if (timestamp == null) {
            throw new InvalidMessageException("Missing " + field);
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
