package dev.orchestrator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Stub-mode end to end: boots the real server on a random port and drives it the
 * way the web UI does (session token, submit, poll, logs, SSE), with the AI CLIs
 * replaced by stubs so no external process or network is needed. This is the
 * "서버 기동 → 명령 → 완료" check from v1-todo #11.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "orchestrator.data-dir=${java.io.tmpdir}/ai-orchestrator-e2e-${random.uuid}",
        "orchestrator.workspace=${java.io.tmpdir}",
        "orchestrator.security.allowed-workspace-roots=${java.io.tmpdir}",
        "orchestrator.modules.claude.mode=STUB",
        "orchestrator.modules.codex.mode=STUB",
        "orchestrator.status.anthropic-status-url=",
        "orchestrator.security.require-token=true",
        "orchestrator.concurrency=2"
})
class StubEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void submitRunsThePipelineToCompletionOverHttp() throws Exception {
        // 1. The same-origin page fetches the install token; without it the API is closed.
        JsonNode session = JSON.readTree(get("/api/session", null).body());
        assertTrue(session.get("tokenRequired").asBoolean());
        String token = session.get("token").asText();
        assertTrue(token.length() >= 32);
        assertEquals(401, get("/api/jobs", null).statusCode(), "no token → 401");
        assertEquals(200, get("/api/jobs", token).statusCode());

        // 2. A cross-site page cannot submit work even with the token.
        HttpResponse<String> crossSite = send(HttpRequest.newBuilder(uri("/api/jobs"))
                .header("Content-Type", "application/json").header("X-Orchestrator-Token", token)
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"commandLine\":\"x\"}")).build());
        assertEquals(403, crossSite.statusCode(), "foreign Origin → 403");

        // 3. Submit the way the palette does: one command line, default preset (1-1-1-1).
        HttpResponse<String> created = post("/api/jobs", token, "{\"commandLine\":\"\\\"jwt refresh token flow\\\" --focus security\"}");
        assertEquals(200, created.statusCode(), created.body());
        JsonNode job = JSON.readTree(created.body());
        String id = job.get("id").asText();
        assertEquals("default", job.get("flow").asText());
        assertEquals("QUEUED", job.get("status").asText(), "steps are planned when the job starts, so the create response has none yet");

        // 4. Poll until terminal, like the job list does between SSE frames.
        JsonNode done = awaitTerminal(id, token);
        assertEquals("SUCCEEDED", done.get("status").asText(), done.toString());
        assertEquals(4, done.get("steps").size(), "planner, coder, reviewer, verifier");
        for (JsonNode step : done.get("steps")) {
            assertEquals("DONE", step.get("status").asText(), step.toString());
        }
        assertFalse(done.get("result").asText().isBlank(), "verifier output is the final result");
        assertNotNull(done.get("finishedAt"));

        // 5. Both log levels exist and the summary tells the story in order.
        JsonNode summary = JSON.readTree(get("/api/jobs/" + id + "/logs?level=SUMMARY", token).body());
        JsonNode detail = JSON.readTree(get("/api/jobs/" + id + "/logs?level=DETAIL", token).body());
        assertTrue(summary.size() >= 6, "queued, started, preset, 4 stages…: " + summary);
        assertTrue(detail.size() > 0);
        List<String> messages = summary.findValuesAsText("message");
        assertTrue(messages.get(0).startsWith("대기열에 추가됨"), messages.get(0));
        assertTrue(messages.stream().anyMatch(m -> m.contains("s1/planner@claude") || m.contains("플래너")), messages.toString());
        long seqOk = summary.findValues("seq").stream().filter(n -> n.asLong() > 0).count();
        assertEquals(summary.size(), seqOk, "every event carries a positive seq");

        // 6. The per-job SSE stream replays the snapshot and the log; the token travels as ?token=.
        String stream = readSse("/api/jobs/" + id + "/events?token=" + token, "event:log");
        assertTrue(stream.contains("event:job"), stream);
        assertTrue(stream.contains("event:log"), stream);
        assertEquals(401, get("/api/jobs/" + id + "/events", null).statusCode(), "SSE also needs the token");

        // 7. Dashboard and list reflect the finished job.
        JsonNode dashboard = JSON.readTree(get("/api/dashboard", token).body());
        assertTrue(dashboard.get("jobCounts").get("SUCCEEDED").asInt() >= 1, dashboard.toString());
        JsonNode list = JSON.readTree(get("/api/jobs", token).body());
        assertTrue(list.findValuesAsText("id").contains(id));

        // 8. Cleanup through the API, like the "삭제" button.
        int deleted = send(HttpRequest.newBuilder(uri("/api/jobs/" + id)).header("X-Orchestrator-Token", token).DELETE().build()).statusCode();
        assertTrue(deleted == 200 || deleted == 204, "delete → " + deleted);
        assertEquals(404, get("/api/jobs/" + id, token).statusCode());
    }

    @Test
    void batchSubmitQueuesSeveralJobsAndPreviewNeedsNoRun() throws Exception {
        String token = JSON.readTree(get("/api/session", null).body()).get("token").asText();

        JsonNode preview = JSON.readTree(post("/api/jobs/preview", token, "{\"commandLine\":\"src/auth/ --preset cross-review\"}").body());
        assertEquals(5, preview.size(), "1-1-2-1 has five agent steps: " + preview);
        assertEquals(0, JSON.readTree(get("/api/jobs", token).body()).findValuesAsText("flow").stream().filter("cross-review"::equals).count(), "preview does not create a job");

        HttpResponse<String> unknown = post("/api/jobs", token, "{\"commandLine\":\"x --preset nope\"}");
        assertEquals(400, unknown.statusCode());
        assertTrue(unknown.body().contains("Unknown flow: nope"), unknown.body());

        JsonNode batch = JSON.readTree(post("/api/jobs/batch", token, "[{\"commandLine\":\"a.ts\"},{\"commandLine\":\"b.ts --preset best-of-3\"}]").body());
        assertEquals(2, batch.size());
        for (JsonNode j : batch) {
            JsonNode done = awaitTerminal(j.get("id").asText(), token);
            // best-of-3 needs a git repo for worktree isolation; the tmp workspace is not one, so that job
            // is refused before spending anything, while the 1-1-1-1 job succeeds.
            String expected = "best-of-3".equals(j.get("flow").asText()) ? "FAILED" : "SUCCEEDED";
            assertEquals(expected, done.get("status").asText(), done.toString());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private JsonNode awaitTerminal(String id, String token) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode job = JSON.readTree(get("/api/jobs/" + id, token).body());
            String status = job.get("status").asText();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job " + id + " did not finish in 30s");
    }

    private String readSse(String path, String until) throws IOException, InterruptedException {
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, res.statusCode());
        StringBuilder sb = new StringBuilder();
        try (InputStream in = res.body()) {
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && sb.indexOf(until) < 0) {
                int n = in.read(buf);
                if (n < 0) break;
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET();
        if (token != null) b.header("X-Orchestrator-Token", token);
        return send(b.build());
    }

    private HttpResponse<String> post(String path, String token, String json) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Orchestrator-Token", token)
                .header("Origin", "http://127.0.0.1:" + port)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
