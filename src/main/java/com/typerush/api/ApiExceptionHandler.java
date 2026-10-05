package com.typerush.api;

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.typerush.security.AuthService;

/**
 * Turns failures into the same {@code {code, message}} shape the rest of the API uses.
 *
 * <p>Without this, a validation failure thrown inside a controller is dispatched to {@code /error},
 * which is an authenticated path, so the client would receive a misleading 401 instead of a 400 that
 * says which field was wrong.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> onInvalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = "request body is not valid";
        }
        return error(HttpStatus.BAD_REQUEST, "invalid_request", detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> onUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("unreadable request body", ex);
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "Request body could not be parsed.");
    }

    /** A path with no handler is a 404, not an authorisation failure. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> onNoResource(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "not_found", "No such endpoint: " + ex.getResourcePath());
    }

    @ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<Map<String, String>> onAuthError(AuthService.AuthException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "rate_limited" -> HttpStatus.TOO_MANY_REQUESTS;
            case "bad_credentials", "bad_refresh" -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, ex.getCode(), ex.getMessage());
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}