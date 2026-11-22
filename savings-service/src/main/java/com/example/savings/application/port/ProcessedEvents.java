package com.example.savings.application.port;

/** Remembers consumed message ids so that redelivered messages are applied only once. */
public interface ProcessedEvents {

    /** @return true if this is the first time the id is seen, false for a duplicate */
    boolean markProcessed(String eventId);
}
