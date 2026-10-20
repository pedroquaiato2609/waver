package br.com.waver.edgegateway;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpCookie;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class EdgeGatewayApplication {

    private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9-]{8,64}");

    public static void main(String[] args) {
        SpringApplication.run(EdgeGatewayApplication.class, args);
    }

    /** Rate-limit key: the browser session when present, otherwise the client address (ADR-0020). */
    @Bean
    KeyResolver sessionOrAddressKeyResolver() {
        return exchange -> {
            HttpCookie session = Optional.ofNullable(exchange.getRequest().getCookies().getFirst("WAVER_CONTROL_SESSION"))
                    .orElse(exchange.getRequest().getCookies().getFirst("WAVER_PARTNER_SESSION"));
            if (session != null) {
                return Mono.just("sess:" + Integer.toHexString(session.getValue().hashCode()));
            }
            String address = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(InetSocketAddress::getHostString).orElse("unknown");
            return Mono.just("addr:" + address);
        };
    }

    /** Sets X-Correlation-Id on every request (CONVENTIONS); a client value is kept only if well-formed. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    GlobalFilter correlationIdFilter() {
        return (exchange, chain) -> {
            String incoming = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
            String id = incoming != null && SAFE_CORRELATION.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
            var request = exchange.getRequest().mutate().headers(h -> h.set("X-Correlation-Id", id)).build();
            exchange.getResponse().getHeaders().set("X-Correlation-Id", id);
            return chain.filter(exchange.mutate().request(request).build());
        };
    }
}
