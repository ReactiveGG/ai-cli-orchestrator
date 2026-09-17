package dev.orchestrator.server.config;

import dev.orchestrator.application.ExecutionManager;
import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AiModule;
import dev.orchestrator.module.CliModuleSettings;
import dev.orchestrator.module.ModuleFactory;
import dev.orchestrator.server.config.OrchestratorProperties.ModuleSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the live {@link ExecutionManager}. Rebuilt whenever the flow config
 * changes (the web UI saves it), so running jobs keep the manager they started
 * with and new jobs pick up the new one.
 */
@Service
public class OrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);
    private static final List<String> MODULE_NAMES = List.of("codex", "claude");

    private final OrchestratorProperties properties;
    private final AtomicReference<ExecutionManager> manager = new AtomicReference<>();

    public OrchestrationService(OrchestratorProperties properties) throws IOException {
        this.properties = properties;
        Files.createDirectories(properties.jobsDir());
        reload(FlowConfig.loadOrDefault(properties.routingFile()));
        log.info("Data dir: {}  workspace: {}", properties.dataDir(), properties.workspaceOrCwd());
        manager.get().modules().forEach((name, module) ->
                log.info("Module {} -> {} ({})", name, module.description(), module.isAvailable() ? "available" : "missing"));
        manager.get().config().flows().values().forEach(flow -> log.info("Flow {}: {}", flow.name(), flow.describe()));
    }

    public ExecutionManager manager() {
        return manager.get();
    }

    public FlowConfig config() {
        return manager.get().config();
    }

    public Map<String, AiModule> modules() {
        return manager.get().modules();
    }

    public synchronized void reload(FlowConfig config) {
        config.validate();
        AiModule[] modules = MODULE_NAMES.stream().map(this::createModule).toArray(AiModule[]::new);
        manager.set(new ExecutionManager(new PromptCompiler(), config, properties.moduleTimeout(), properties.idleWarning(), modules));
    }

    private AiModule createModule(String name) {
        ModuleSettings settings = properties.moduleSettings(name);
        String command = settings.command() == null || settings.command().isBlank() ? name : settings.command();
        return ModuleFactory.create(name, settings.mode(),
                new CliModuleSettings(command, settings.extraArgs(), properties.workspaceOrCwd()));
    }
}
