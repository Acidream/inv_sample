package com.example.savings.adapter.web;

import com.example.savings.adapter.web.StatementCursorCodec.InvalidCursorException;
import com.example.savings.application.UnknownAccountException;
import com.example.savings.application.order.OrderIdInUseException;
import com.example.savings.application.order.UnknownOrderException;
import com.example.savings.application.port.CommandLog.CommandLogUnavailableException;
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

    /** Nothing was accepted; the client retries with the same order id. */
    @ExceptionHandler(CommandLogUnavailableException.class)
    ProblemDetail commandLogUnavailable(CommandLogUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(InvalidCursorException.class)
    ProblemDetail invalidCursor(InvalidCursorException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
