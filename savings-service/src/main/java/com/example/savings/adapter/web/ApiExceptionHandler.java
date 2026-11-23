package com.example.savings.adapter.web;

import com.example.savings.adapter.web.StatementCursorCodec.InvalidCursorException;
import com.example.savings.application.UnknownAccountException;
import com.example.savings.application.order.OrderIdInUseException;
import com.example.savings.application.order.UnknownOrderException;
import com.example.savings.application.port.CommandLog.CommandLogUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errors as RFC 9457 problem details. The base class covers validation and binding errors, including
 * Spring MVC's built-in method validation of path variables and parameters (no {@code @Validated}).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnknownAccountException.class)
    ProblemDetail unknownAccount(UnknownAccountException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Also the answer until an accepted order has been applied: the client polls. */
    @ExceptionHandler(UnknownOrderException.class)
    ProblemDetail unknownOrder(UnknownOrderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(OrderIdInUseException.class)
    ProblemDetail orderIdInUse(OrderIdInUseException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /**
     * Not confirmed, which is not the same as not accepted: the send may still reach Kafka after the
     * intake stopped waiting, and then it is applied. A retry with the same key is always safe.
     */
    @ExceptionHandler(CommandLogUnavailableException.class)
    ProblemDetail commandLogUnavailable(CommandLogUnavailableException e) {
        log.warn("Answering 503: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Kafka did not confirm the request in time; it may or may not have been accepted."
                + " Retry with the same Idempotency-Key: a retry never creates a second one.");
    }

    @ExceptionHandler(InvalidCursorException.class)
    ProblemDetail invalidCursor(InvalidCursorException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
