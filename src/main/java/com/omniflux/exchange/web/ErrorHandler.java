package com.omniflux.exchange.web;

import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ServerWebInputException;

/** Converts domain failures into stable, non-sensitive JSON responses. */
@RestControllerAdvice
public final class ErrorHandler {
    private static final String REQUEST_FAILED = "request failed";
    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ExportException.class)
    public ResponseEntity<ProblemDetail> exportFailure(ExportException error) {
        String detail = error.detail();
        return problem(HttpStatus.valueOf(error.code().httpStatus()), error.code().name(),
                detail == null || detail.isBlank() ? REQUEST_FAILED : detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException error) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", safeMessage(error));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> malformedInput(ServerWebInputException error) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "request input is invalid");
    }

    /** Spring 7 routes unmatched paths to the static-resource handler, which
     *  throws this 404-carrying type; the catch-all below would otherwise
     *  upgrade every unknown route to a 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(NoResourceFoundException error) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such route");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception error) {
        LOG.error("Unhandled web request failure", error);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(), REQUEST_FAILED);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        detail.setProperty("message", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? REQUEST_FAILED : message;
    }
}
