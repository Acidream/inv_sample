package com.example.currentaccount.adapter.web;

import com.example.currentaccount.application.AccountIdInUseException;
import com.example.currentaccount.application.PaymentIdInUseException;
import com.example.currentaccount.application.UnknownAccountException;
import com.example.currentaccount.application.UnknownPaymentException;
import com.example.currentaccount.application.port.CommandLog.CommandLogUnavailableException;
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

    /** Also the answer until an accepted opening has been applied: the client polls. */
    @ExceptionHandler(UnknownAccountException.class)
    ProblemDetail unknownAccount(UnknownAccountException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UnknownPaymentException.class)
    ProblemDetail unknownPayment(UnknownPaymentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({AccountIdInUseException.class, PaymentIdInUseException.class})
    ProblemDetail idInUse(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /** Nothing was accepted; the client retries with the same id. */
    @ExceptionHandler(CommandLogUnavailableException.class)
    ProblemDetail commandLogUnavailable(CommandLogUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
