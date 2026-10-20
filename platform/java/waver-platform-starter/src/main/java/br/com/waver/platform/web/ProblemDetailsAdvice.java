package br.com.waver.platform.web;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.platform.http.RemoteProblem;
import br.com.waver.platform.tenancy.TenantContextHolder;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem+json with a stable `code` and the `correlationId` (CONVENTIONS, STD-008).
 * Server details never reach the client; they are logged with the correlation ID.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return problem(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request has invalid fields.");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "code", String.valueOf(f.getCode()), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action.");
    }

    /** A rule of the owning service (4xx) keeps its status and code; a failing or unreachable dependency is a 503. */
    @ExceptionHandler(RemoteProblem.class)
    ProblemDetail remote(RemoteProblem e) {
        if (e.status() >= 400 && e.status() < 500 && e.status() != 401) {
            return problem(HttpStatus.valueOf(e.status()), e.code(), e.getMessage());
        }
        log.warn("Dependency failed [correlationId={}, status={}, code={}]: {}", CorrelationIdFilter.current(), e.status(), e.code(), e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A service this action depends on is unavailable. Try again.");
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    ProblemDetail unreachable(org.springframework.web.client.ResourceAccessException e) {
        log.warn("Dependency unreachable [correlationId={}]: {}", CorrelationIdFilter.current(), e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A service this action depends on is unavailable. Try again.");
    }

    @ExceptionHandler(TenantContextHolder.TenantContextMissingException.class)
    ProblemDetail tenantMissing(TenantContextHolder.TenantContextMissingException e) {
        return problem(HttpStatus.FORBIDDEN, "TENANT_CONTEXT_MISSING", "The token does not carry a WAVER organization.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error [correlationId={}]", CorrelationIdFilter.current(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error. Quote the correlation ID to support.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:waver:problem:" + code));
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        p.setProperty("correlationId", CorrelationIdFilter.current());
        return p;
    }
}
