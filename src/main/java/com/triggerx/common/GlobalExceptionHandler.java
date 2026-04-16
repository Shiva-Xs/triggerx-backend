package com.triggerx.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

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
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST", message, null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String val = ex.getValue() != null ? ex.getValue().toString() : "<empty>";
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST",
                        ex.getName() + ": invalid value '" + val + "'", null));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST",
                        ex.getParameterName() + " is required", null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiResponse("INVALID_REQUEST",
                        "Invalid request body — check field types and enum values", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError()
                .body(new ApiResponse("INTERNAL_ERROR", "An unexpected error occurred", null));
    }
}
