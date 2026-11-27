
package com.abcbank.mobile_banking.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ApiExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> response(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> validationErrors) {

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("validationErrors", validationErrors);

        return ResponseEntity.status(status).body(body);
    }

    // 400 - Request body validation
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage()
                )
        );

        return response(
                HttpStatus.BAD_REQUEST,
                "Please correct the submitted fields.",
                request,
                errors
        );
    }

    // 400 - Parameter validation
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();

        ex.getConstraintViolations().forEach(error ->
                errors.put(
                        error.getPropertyPath().toString(),
                        error.getMessage()
                )
        );

        return response(
                HttpStatus.BAD_REQUEST,
                "Request validation failed.",
                request,
                errors
        );
    }

    // 400 - Invalid JSON
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.BAD_REQUEST,
                "Invalid request body. Please check your JSON format.",
                request,
                Map.of()
        );
    }

    // 400 - Business validation
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.BAD_REQUEST,
                safeMessage(ex.getMessage(), "Invalid request."),
                request,
                Map.of()
        );
    }

    // ResponseStatusException
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(
            ResponseStatusException ex,
            HttpServletRequest request) {

        HttpStatus status = HttpStatus.valueOf(
                ex.getStatusCode().value()
        );

        return response(
                status,
                safeMessage(ex.getReason(), status.getReasonPhrase()),
                request,
                Map.of()
        );
    }

    // 404 - Missing resource
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.NOT_FOUND,
                "The requested API resource was not found.",
                request,
                Map.of()
        );
    }

    // 409 - Database conflict
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> databaseConflict(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        log.warn("Database constraint violation at {}",
                request.getRequestURI());

        return response(
                HttpStatus.CONFLICT,
                "The operation conflicts with existing data.",
                request,
                Map.of()
        );
    }

    // 401 - Authentication
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> authentication(
            AuthenticationException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.UNAUTHORIZED,
                "Authentication failed. Please login again.",
                request,
                Map.of()
        );
    }

    // 403 - Permission
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.FORBIDDEN,
                "You do not have permission to perform this operation.",
                request,
                Map.of()
        );
    }

    // 500 - Unexpected errors
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(
            Exception ex,
            HttpServletRequest request) {

        log.error(
                "Unhandled API error at {}",
                request.getRequestURI(),
                ex
        );

        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Request could not be completed. Please try again later.",
                request,
                Map.of()
        );
    }

    private String safeMessage(String message, String fallback) {
        return (message == null || message.isBlank())
                ? fallback
                : message;
    }
}
