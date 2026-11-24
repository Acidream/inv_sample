package com.example.investments.application.port;

import com.example.investments.application.Command;

/** The service's own input topic, {@code investments.commands.v1} (ADR 0019). */
public interface CommandLog {

    /**
     * @throws CommandLogUnavailableException if the log didn't confirm the command in time; it may still
     *                                        be stored and applied, so the client retries with the same key
     */
    void append(Command command);

    class CommandLogUnavailableException extends RuntimeException {

        public CommandLogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
