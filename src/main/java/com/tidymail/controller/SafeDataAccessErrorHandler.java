package com.tidymail.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler that converts database access failures into a safe,
 * generic 503 response.
 *
 * <p>Product functionality supported: <em>resilience / error handling</em>
 * across all pages. Rather than leaking SQL details or stack traces to the
 * browser, any {@link DataAccessException} is logged server-side and surfaced
 * as a plain &quot;temporarily unavailable&quot; message with security headers
 * (no-store, nosniff, no-referrer).</p>
 */
@RestControllerAdvice
public class SafeDataAccessErrorHandler {
    private static final Logger LOG =
        LoggerFactory.getLogger(SafeDataAccessErrorHandler.class);

    /**
     * Handles a database access failure by logging it and returning a generic
     * 503 Service Unavailable response with hardened headers.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> unavailable(DataAccessException failure) {
        LOG.error("Database request failed: {}",
            failure.getClass().getSimpleName(), failure);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .contentType(MediaType.TEXT_PLAIN)
            .body("The requested data is temporarily unavailable.");
    }
}
