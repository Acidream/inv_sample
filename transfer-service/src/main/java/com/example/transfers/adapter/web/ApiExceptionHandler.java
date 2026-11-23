package com.example.transfers.adapter.web;

import com.example.transfers.application.TransferIdInUseException;
import com.example.transfers.application.UnknownTransferException;
import com.example.transfers.application.port.RequestLog.RequestLogUnavailableException;
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

    /** Also the answer until an accepted request has been applied: the client polls. */
    @ExceptionHandler(UnknownTransferException.class)
    ProblemDetail unknownTransfer(UnknownTransferException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TransferIdInUseException.class)
    ProblemDetail transferIdInUse(TransferIdInUseException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /** Nothing was accepted; the client retries with the same transfer id. */
    @ExceptionHandler(RequestLogUnavailableException.class)
    ProblemDetail requestLogUnavailable(RequestLogUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
