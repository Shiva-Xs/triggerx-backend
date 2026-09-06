package com.triggerx.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TriggerXException.class)
    public ResponseEntity<ApiResponse> handle(TriggerXException ex) {
        var builder = ResponseEntity.status(ex.getStatus());

        if (ex.getRetryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        }

        return builder.body(new ApiResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                ex.getAttemptsRemaining()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST", "Invalid request. Please check your inputs.", null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST",
                        "One or more fields have invalid values.", null));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST",
                        "A required field is missing.", null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST",
                        "Invalid request body — check field types and enum values", null));
    }

    /**
     * A path with no handler - /api/v1/symbols/nope, and every scanner probe under a
     * permit-listed prefix - used to fall through to the catch-all below and answer 500 with a
     * full stack trace in the log. A missing route is a 404, and saying so keeps genuine 500s
     * meaningful.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse> handleNoResource(NoResourceFoundException ex) {
        log.debug("No handler for {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Robots-Tag", "noindex")
                .body(new ApiResponse("NOT_FOUND", "No such resource", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError()
                .body(new ApiResponse("INTERNAL_ERROR", "An unexpected error occurred", null));
    }
}
