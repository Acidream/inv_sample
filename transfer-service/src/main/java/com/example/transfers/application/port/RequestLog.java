package com.example.transfers.application.port;

import com.example.transfers.domain.Transfer;

/**
 * The transfer service's command log, {@code payments.transfer-requests.v1} (ADR 0019). The REST
 * intake appends here; requests are applied when consumed, like the ones other services send.
 */
public interface RequestLog {

    /** @throws RequestLogUnavailableException if the request couldn't be stored; the client retries */
    void append(Transfer.Request request);

    class RequestLogUnavailableException extends RuntimeException {

        public RequestLogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
