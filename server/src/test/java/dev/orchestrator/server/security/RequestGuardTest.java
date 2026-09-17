package dev.orchestrator.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.orchestrator.server.security.RequestGuard.Request;
import dev.orchestrator.server.security.RequestGuard.Verdict;
import org.junit.jupiter.api.Test;

class RequestGuardTest {
    private static Request req(String method, String host, String origin, String headerToken, String auth, String query) {
        return new Request(method, "/api/jobs", host, origin, headerToken, auth, query);
    }

    @Test
    void refusesNonLoopbackHostsAgainstDnsRebinding() {
        RequestGuard guard = new RequestGuard(null);
        assertEquals(Verdict.FORBIDDEN_HOST, guard.evaluate(req("GET", "evil.example:47120", null, null, null, null)));
        assertEquals(Verdict.FORBIDDEN_HOST, guard.evaluate(req("GET", "192.168.0.5:47120", null, null, null, null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("GET", "localhost:47120", null, null, null, null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("GET", "127.0.0.1", null, null, null, null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("GET", "[::1]:47120", null, null, null, null)));
    }

    @Test
    void blocksCrossSiteWritesButAllowsLocalPagesAndReads() {
        RequestGuard guard = new RequestGuard(null);
        assertFalse(guard.tokenRequired());
        assertEquals(Verdict.ALLOW, guard.evaluate(req("GET", "localhost", "https://evil.example", null, null, null)), "reads are harmless (responses are not readable cross-origin)");
        assertEquals(Verdict.FORBIDDEN_ORIGIN, guard.evaluate(req("POST", "localhost", "https://evil.example", null, null, null)));
        assertEquals(Verdict.FORBIDDEN_ORIGIN, guard.evaluate(req("PUT", "localhost", "http://192.168.0.5:47120", null, null, null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("POST", "localhost", "http://localhost:47120", null, null, null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("DELETE", "localhost", "http://127.0.0.1:5173", null, null, null)), "Vite dev server on loopback");
        assertEquals(Verdict.ALLOW, guard.evaluate(req("POST", "localhost", null, null, null, null)), "curl and same-origin fetch send no Origin");
        assertEquals(Verdict.ALLOW, guard.evaluate(req("POST", "localhost", "null", null, null, null)), "opaque origin from local file: pages");
    }

    @Test
    void tokenIsRequiredEverywhereExceptTheSessionEndpoint() {
        RequestGuard guard = new RequestGuard("s3cret");
        assertEquals(Verdict.MISSING_TOKEN, guard.evaluate(req("GET", "localhost", null, null, null, null)));
        assertEquals(Verdict.MISSING_TOKEN, guard.evaluate(req("GET", "localhost", null, "wrong", null, null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("GET", "localhost", null, "s3cret", null, null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("POST", "localhost", "http://localhost:47120", null, "Bearer s3cret", null)));
        assertEquals(Verdict.ALLOW, guard.evaluate(req("GET", "localhost", null, null, null, "s3cret")), "EventSource passes ?token=");
        assertEquals(Verdict.ALLOW, guard.evaluate(new Request("GET", "/api/session", "localhost", null, null, null, null)), "the page fetches its token here");
        assertEquals(Verdict.FORBIDDEN_HOST, guard.evaluate(new Request("GET", "/api/session", "evil.example", null, null, null, null)), "but never via a rebound host");
        assertEquals(Verdict.FORBIDDEN_ORIGIN, guard.evaluate(req("POST", "localhost", "https://evil.example", "s3cret", null, null)), "token does not lift the origin rule");
    }
}
