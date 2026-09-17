package dev.orchestrator.server.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Access rules for {@code /api/**}, kept free of servlet types so they are easy to test.
 *
 * <ul>
 *   <li><b>Host check</b>: the {@code Host} header must be loopback. A page on another
 *       site whose DNS name resolves to 127.0.0.1 (DNS rebinding) sends its own host
 *       name and is refused.</li>
 *   <li><b>Origin check</b>: state-changing requests with a non-loopback {@code Origin}
 *       are refused, so a malicious page cannot drive the orchestrator through the
 *       user's browser.</li>
 *   <li><b>Token</b>: when required, every request must carry the install's API token
 *       ({@code X-Orchestrator-Token} header, {@code Authorization: Bearer}, or the
 *       {@code token} query parameter for EventSource). Only the same-origin page can
 *       obtain it from {@code GET /api/session}, which is exempt.</li>
 * </ul>
 */
public final class RequestGuard {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1", "0:0:0:0:0:0:0:1");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    public static final String SESSION_PATH = "/api/session";

    public enum Verdict { ALLOW, FORBIDDEN_HOST, FORBIDDEN_ORIGIN, MISSING_TOKEN }

    /** Request facts the rules look at. Any header may be null. */
    public record Request(String method, String path, String host, String origin, String headerToken, String authorization, String queryToken) {
    }

    private final String token;

    /** @param token the token every request must present, or null when tokens are not required */
    public RequestGuard(String token) {
        this.token = token == null || token.isBlank() ? null : token.trim();
    }

    public boolean tokenRequired() {
        return token != null;
    }

    public Verdict evaluate(Request r) {
        if (r.host() != null && !isLoopbackHost(r.host())) {
            return Verdict.FORBIDDEN_HOST;
        }
        boolean mutating = !SAFE_METHODS.contains(r.method().toUpperCase(Locale.ROOT));
        if (mutating && r.origin() != null && !r.origin().isBlank() && !"null".equals(r.origin()) && !isLoopbackOrigin(r.origin())) {
            return Verdict.FORBIDDEN_ORIGIN;
        }
        if (token != null && !SESSION_PATH.equals(r.path())
                && !token.equals(r.headerToken()) && !("Bearer " + token).equals(r.authorization()) && !token.equals(r.queryToken())) {
            return Verdict.MISSING_TOKEN;
        }
        return Verdict.ALLOW;
    }

    static boolean isLoopbackOrigin(String origin) {
        try {
            String host = URI.create(origin.trim()).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code Host} header value, e.g. {@code localhost:47120} or {@code [::1]:47120}. */
    static boolean isLoopbackHost(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 && LOOPBACK.contains(h.substring(0, end + 1));
        }
        int colon = h.indexOf(':');
        String host = colon >= 0 ? h.substring(0, colon) : h;
        return LOOPBACK.contains(host);
    }
}
