package com.example.currentaccount.application.port;

import com.example.currentaccount.application.Command;

/** The service's own input topic (ADR 0019). */
public interface CommandLog {

    /**
     * @throws CommandLogUnavailableException if the log didn't confirm the command in time; it may still
     *                                        be stored and applied, so the client retries with the same id
     */
    void append(Command command);

    class CommandLogUnavailableException extends RuntimeException {

        public CommandLogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
