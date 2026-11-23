package com.example.platform.messaging;

/**
 * A consumed message that can never be applied as it stands: a value missing, malformed, or one the
 * database would refuse. Thrown by {@link WireValues} and by each service's anti-corruption layer;
 * {@link DeadLetters} parks the message on the dead-letter topic instead of retrying it.
 *
 * <p>A plain {@link IllegalArgumentException} is <em>not</em> dead-lettered: thrown from deeper down
 * (a library, a bug) it says nothing about the message, so the message is retried and the blocked
 * partition is reported, rather than a valid instruction parked.
 */
public class InvalidMessageException extends IllegalArgumentException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
