package br.com.waver.platform.http;

import br.com.waver.platform.web.CorrelationIdFilter;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Forwards the caller's validated access token and correlation ID to the next service, so it applies the same
 * tenant context and roles (AR-008). Token exchange to a down-scoped token (RFC-0004) replaces the plain relay
 * once the exchange policy is configured in Keycloak.
 */
public class BearerRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            throw new IllegalStateException("No user token to relay: call this client only while serving an authenticated request");
        }
        request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
        request.getHeaders().set(CorrelationIdFilter.HEADER, CorrelationIdFilter.current());
        return execution.execute(request, body);
    }
}
