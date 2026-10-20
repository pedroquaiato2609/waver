package br.com.waver.platform.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Error answered by another WAVER service (RFC 9457 problem+json with a stable {@code code}). The platform advice
 * answers 4xx problems with the same status and code, so callers see the owning service's rule; 5xx and
 * unreachable services become 503 DEPENDENCY_UNAVAILABLE (STD-008).
 */
public class RemoteProblem extends RuntimeException {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int status;
    private final String code;

    public RemoteProblem(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** RestClient status handler for every 4xx/5xx: {@code builder.defaultStatusHandler(HttpStatusCode::isError, RemoteProblem::raise)}. */
    public static void raise(org.springframework.http.HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String code = "DEPENDENCY_ERROR";
        String detail = request.getMethod() + " " + request.getURI().getPath() + " answered " + status.value();
        try (InputStream body = response.getBody()) {
            byte[] bytes = body.readAllBytes();
            if (bytes.length > 0) {
                Map<?, ?> problem = JSON.readValue(bytes, Map.class);
                if (problem.get("code") instanceof String c) {
                    code = c;
                }
                if (problem.get("detail") instanceof String d) {
                    detail = d;
                }
            }
        } catch (RuntimeException notJson) {
            // keep the generic code
        }
        throw new RemoteProblem(status.value(), code, detail);
    }

    /** Applies the handler to a builder. */
    public static RestClient.Builder handled(RestClient.Builder builder) {
        return builder.defaultStatusHandler(HttpStatusCode::isError, RemoteProblem::raise);
    }
}
