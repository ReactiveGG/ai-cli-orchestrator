package dev.orchestrator.server.api;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.server.config.OrchestrationService;
import dev.orchestrator.server.config.OrchestratorProperties;
import dev.orchestrator.server.config.RuntimeSettings;
import dev.orchestrator.server.config.FlowConfigDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow configuration as edited by the drag-and-drop builder. Saving writes
 * {@code <dataDir>/orchestrator.yml} and hot-reloads the execution manager.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {
    /** Runtime settings plus read-only paths. */
    public record Settings(
            String dataDir, String routingFile, String settingsFile,
            String workspace, int concurrency, long moduleTimeoutSeconds, long idleWarningSeconds,
            java.util.Map<String, RuntimeSettings.ModuleSnapshot> modules,
            RuntimeSettings.IsolationSnapshot isolation
    ) {
        static Settings of(OrchestratorProperties p, RuntimeSettings rs) {
            RuntimeSettings.Snapshot s = rs.current();
            return new Settings(p.dataDir().toString(), p.routingFile().toString(), rs.file().toString(),
                    s.workspace(), s.concurrency(), s.moduleTimeoutSeconds(), s.idleWarningSeconds(), s.modules(), s.isolation());
        }

        RuntimeSettings.Snapshot toSnapshot() {
            return new RuntimeSettings.Snapshot(workspace, concurrency, moduleTimeoutSeconds, idleWarningSeconds, modules, isolation);
        }
    }

    private final OrchestrationService orchestration;
    private final OrchestratorProperties properties;
    private final dev.orchestrator.server.job.JobService jobs;

    private final dev.orchestrator.server.config.FolderDialog folderDialog;

    public ConfigController(OrchestrationService orchestration, OrchestratorProperties properties, dev.orchestrator.server.job.JobService jobs, dev.orchestrator.server.config.FolderDialog folderDialog) {
        this.folderDialog = folderDialog;
        this.orchestration = orchestration;
        this.properties = properties;
        this.jobs = jobs;
    }

    @GetMapping("/routing")
    public FlowConfigDto routing() {
        return FlowConfigDto.from(orchestration.config());
    }

    @PutMapping("/routing")
    public FlowConfigDto saveRouting(@RequestBody FlowConfigDto dto) throws IOException {
        FlowConfig config = dto.toConfig();
        Path file = properties.routingFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, dto.toYaml(), StandardCharsets.UTF_8);
        orchestration.reload(config);
        return FlowConfigDto.from(orchestration.config());
    }

    @GetMapping(value = "/routing.yaml", produces = MediaType.TEXT_PLAIN_VALUE)
    public String routingYaml() {
        return FlowConfigDto.from(orchestration.config()).toYaml();
    }

    /** Resets to the built-in defaults (or the single-module preset when {@code preset=single:<module>}). */
    @PostMapping("/routing/reset")
    public FlowConfigDto reset(@RequestBody(required = false) java.util.Map<String, String> body) throws IOException {
        String preset = body == null ? null : body.get("preset");
        FlowConfig config = preset != null && preset.startsWith("single:")
                ? FlowConfig.singleModule(preset.substring("single:".length()))
                : FlowConfig.defaultConfig();
        FlowConfigDto dto = FlowConfigDto.from(config);
        Path file = properties.routingFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, dto.toYaml(), StandardCharsets.UTF_8);
        orchestration.reload(config);
        return dto;
    }

    /** Opens the OS folder dialog on the server's desktop and returns the chosen path ({@code path} null when cancelled). */
    @org.springframework.web.bind.annotation.PostMapping("/settings/browse")
    public java.util.Map<String, Object> browse(@RequestBody(required = false) java.util.Map<String, String> body) throws java.io.IOException, InterruptedException {
        String initial = body == null ? null : body.get("initial");
        java.util.Optional<String> picked = folderDialog.pick(initial);
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("path", picked.orElse(null));
        out.put("backend", folderDialog.backend().name());
        return out;
    }

    @GetMapping("/settings")
    public Settings settings() {
        return Settings.of(properties, orchestration.settings());
    }

    /** Saves runtime settings to {@code <data-dir>/settings.yml} and applies them (new jobs use them). */
    @PutMapping("/settings")
    public Settings saveSettings(@RequestBody Settings body) {
        orchestration.settings().update(body.toSnapshot());
        orchestration.applySettings();
        jobs.setConcurrency(orchestration.settings().current().concurrency());
        return Settings.of(properties, orchestration.settings());
    }
}
