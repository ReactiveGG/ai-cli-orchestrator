package dev.orchestrator.server.status;

import dev.orchestrator.domain.AiModule;
import dev.orchestrator.module.ProcessRunner;
import dev.orchestrator.server.config.OrchestrationService;
import dev.orchestrator.server.config.OrchestratorProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Two kinds of "is Claude OK": the local CLI (installed? version?) and the
 * public Anthropic status page. Both are cached for {@code orchestrator.status.cache-ttl}.
 */
@Service
public class ClaudeStatusService {
    private static final Logger log = LoggerFactory.getLogger(ClaudeStatusService.class);

    /**
     * @param loggedIn   {@code claude auth status} result: true/false, or null when unknown (stub, codex, probe failed)
     * @param authMethod e.g. {@code claude.ai} or {@code console}, when logged in
     */
    public record ModuleStatus(String name, String description, boolean available, String version, String mode, Boolean loggedIn, String authMethod) {
    }

    public record RemoteStatus(String indicator, String description, Instant checkedAt) {
    }

    public record StatusReport(List<ModuleStatus> modules, RemoteStatus anthropic) {
    }

    private final OrchestrationService orchestration;
    private final OrchestratorProperties properties;
    private final RestClient restClient = RestClient.builder().build();
    private final Map<String, String> versionCache = new LinkedHashMap<>();
    private volatile RemoteStatus remote = new RemoteStatus("unknown", "아직 확인 안 함", null);
    private volatile StatusReport cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public ClaudeStatusService(OrchestrationService orchestration, OrchestratorProperties properties) {
        this.orchestration = orchestration;
        this.properties = properties;
    }

    public StatusReport report() {
        Duration ttl = properties.status().cacheTtl();
        if (cached != null && Duration.between(cachedAt, Instant.now()).compareTo(ttl) < 0) {
            return cached;
        }
        synchronized (this) {
            if (cached != null && Duration.between(cachedAt, Instant.now()).compareTo(ttl) < 0) {
                return cached;
            }
            List<ModuleStatus> modules = orchestration.modules().values().stream().map(this::describe).toList();
            remote = fetchRemote();
            cached = new StatusReport(modules, remote);
            cachedAt = Instant.now();
            return cached;
        }
    }

    private ModuleStatus describe(AiModule module) {
        boolean available = module.isAvailable();
        String command = orchestration.settings().module(module.name()).command();
        if (command == null || command.isBlank()) {
            command = module.name();
        }
        String mode = module.description().startsWith("cli") ? "cli" : "stub";
        String version = "cli".equals(mode) && available ? versionCache.computeIfAbsent(command, this::probeVersion) : null;
        AuthStatus auth = "cli".equals(mode) && available && "claude".equals(module.name()) ? probeAuth(command) : null;
        return new ModuleStatus(module.name(), module.description(), available, version, mode,
                auth == null ? null : auth.loggedIn(), auth == null ? null : auth.authMethod());
    }

    public record AuthStatus(Boolean loggedIn, String authMethod) {
    }

    /** Parses {@code claude auth status} JSON ({"loggedIn": true, "authMethod": "claude.ai", ...}); null when it is not that JSON. */
    static AuthStatus parseAuth(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.substring(start, end + 1));
            if (!node.has("loggedIn")) {
                return null;
            }
            return new AuthStatus(node.path("loggedIn").asBoolean(false), node.hasNonNull("authMethod") ? node.path("authMethod").asText() : null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Runs {@code <command> auth status} (Claude Code 2.x) so the dashboard can say "로그인 필요" before a job burns a slot. */
    private AuthStatus probeAuth(String command) {
        if (!ProcessRunner.isOnPath(command)) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(ProcessRunner.launchCommand(List.of(command, "auth", "status"))).redirectErrorStream(true).start();
            String text;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                text = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return parseAuth(text);
        } catch (Exception e) {
            log.debug("Auth probe for {} failed: {}", command, e.toString());
            return null;
        } finally {
            Thread.interrupted();
        }
    }

    private String probeVersion(String command) {
        if (!ProcessRunner.isOnPath(command)) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(ProcessRunner.launchCommand(List.of(command, "--version"))).redirectErrorStream(true).start();
            String line;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return line == null ? null : line.strip();
        } catch (Exception e) {
            log.debug("Version probe for {} failed: {}", command, e.toString());
            return null;
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private RemoteStatus fetchRemote() {
        String url = properties.status().anthropicStatusUrl();
        if (url == null || url.isBlank()) {
            return new RemoteStatus("disabled", "상태 페이지 조회 비활성화", Instant.now());
        }
        try {
            Map<String, Object> body = restClient.get().uri(url).retrieve().body(Map.class);
            Map<String, Object> status = body == null ? null : (Map<String, Object>) body.get("status");
            if (status == null) {
                return new RemoteStatus("unknown", "응답 형식을 해석할 수 없음", Instant.now());
            }
            return new RemoteStatus(
                    String.valueOf(status.getOrDefault("indicator", "unknown")),
                    String.valueOf(status.getOrDefault("description", "")),
                    Instant.now());
        } catch (Exception e) {
            log.debug("Anthropic status fetch failed: {}", e.toString());
            return new RemoteStatus("unreachable", "상태 페이지에 연결할 수 없음", Instant.now());
        }
    }
}
