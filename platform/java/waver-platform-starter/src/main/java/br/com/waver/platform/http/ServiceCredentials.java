package br.com.waver.platform.http;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Service-account token of this service ({@code svc-<service>} client, client credentials, ADR-0021), cached until
 * shortly before it expires. Used for calls made without a user: workflow activities, event consumers, jobs.
 * Calls made while serving a user request relay the user's token instead ({@link BearerRelayInterceptor}).
 */
public class ServiceCredentials implements ClientHttpRequestInterceptor {

    private final RestClient http;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ServiceCredentials(RestClient.Builder builder, String tokenUri, String clientId, String clientSecret) {
        this.http = builder.clone().build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String clientId() {
        return clientId;
    }

    public synchronized String accessToken() {
        if (token == null || Instant.now().isAfter(expiresAt.minusSeconds(30))) {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            Map<?, ?> response = http.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("access_token") == null) {
                throw new IllegalStateException("Token endpoint returned no access token for " + clientId);
            }
            token = (String) response.get("access_token");
            expiresAt = Instant.now().plusSeconds(((Number) response.get("expires_in")).longValue());
        }
        return token;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws java.io.IOException {
        request.getHeaders().setBearerAuth(accessToken());
        return execution.execute(request, body);
    }
}
