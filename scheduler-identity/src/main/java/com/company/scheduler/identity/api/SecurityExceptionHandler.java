package com.company.scheduler.identity.api;

import com.company.scheduler.shared.api.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Preserve security status codes for method-level authorization and application services. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SecurityExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(ApiError.of("ACCESS_DENIED", "无访问权限"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(AuthenticationException ex) {
        return ResponseEntity.status(401).body(ApiError.of("UNAUTHENTICATED", "请先登录"));
    }
}
