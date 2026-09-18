package dev.orchestrator.server.api;

import dev.orchestrator.server.security.ApiToken;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands the API token to the same-origin web page. Other sites cannot read this
 * response (no CORS headers) and non-loopback hosts are refused by the guard,
 * so the token stays with the local UI.
 */
@RestController
public class SessionController {
    private final ApiToken token;
    private final String version;

    public SessionController(ApiToken token, org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> build) {
        this.token = token;
        org.springframework.boot.info.BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    @GetMapping("/api/session")
    public Map<String, Object> session() {
        return Map.of("tokenRequired", token.value() != null, "token", token.value() == null ? "" : token.value(), "version", version);
    }
}
