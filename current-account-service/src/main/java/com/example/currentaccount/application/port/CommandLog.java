package com.example.currentaccount.application.port;

import com.example.currentaccount.application.Command;

/** The service's own input topic (ADR 0019). */
public interface CommandLog {

    /** @throws CommandLogUnavailableException if the command could not be appended; nothing was accepted */
    void append(Command command);

    class CommandLogUnavailableException extends RuntimeException {

        public CommandLogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
