package br.com.waver.platform.security;

import br.com.waver.platform.tenancy.TenantContextAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Builds the tenant context from the validated JWT for the duration of the request (AR-008). */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            WaverJwt.tenantContext(jwtAuth.getToken()).ifPresent(TenantContextAccess::set);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContextAccess.clear();
        }
    }
}
