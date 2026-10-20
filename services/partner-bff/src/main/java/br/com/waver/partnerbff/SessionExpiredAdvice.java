package br.com.waver.partnerbff;

import br.com.waver.platform.web.CorrelationIdFilter;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The user's tokens are gone (expired refresh token, revoked session): answer 401 so the SPA signs in again. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SessionExpiredAdvice {

    @ExceptionHandler(ClientAuthorizationRequiredException.class)
    ProblemDetail sessionExpired(ClientAuthorizationRequiredException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Your session expired. Sign in again.");
        p.setType(URI.create("urn:waver:problem:SESSION_EXPIRED"));
        p.setProperty("code", "SESSION_EXPIRED");
        p.setProperty("correlationId", CorrelationIdFilter.current());
        return p;
    }
}
