package dev.orchestrator.server.api;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.server.config.OrchestrationService;
import dev.orchestrator.server.config.OrchestratorProperties;
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
    public record Settings(String dataDir, String workspace, String routingFile, int concurrency,
                           long moduleTimeoutSeconds, long idleWarningSeconds) {
    }

    private final OrchestrationService orchestration;
    private final OrchestratorProperties properties;

    public ConfigController(OrchestrationService orchestration, OrchestratorProperties properties) {
        this.orchestration = orchestration;
        this.properties = properties;
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

    @GetMapping("/settings")
    public Settings settings() {
        return new Settings(
                properties.dataDir().toString(),
                properties.workspaceOrCwd().toString(),
                properties.routingFile().toString(),
                properties.concurrency(),
                properties.moduleTimeout().toSeconds(),
                properties.idleWarning().toSeconds()
        );
    }
}
