package dev.orchestrator.server.api;

import dev.orchestrator.catalog.CommandCatalog;
import dev.orchestrator.domain.AiModule;
import dev.orchestrator.domain.FlowDefinition;
import dev.orchestrator.server.config.OrchestrationService;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything the command palette needs: flows, options, modules, roles. */
@RestController
public class CatalogController {
    public record ModuleInfo(String name, String description, boolean available) {
    }

    public record RoleInfo(String name, String label, String instructions) {
    }

    public record StageInfo(String name, String role, List<String> models) {
    }

    public record FlowInfo(String name, String label, String signature, String task, String description, List<StageInfo> stages, List<String> defaultFocus) {
    }

    public record Catalog(
            List<FlowInfo> flows,
            List<CommandCatalog.OptionSpec> options,
            List<ModuleInfo> modules,
            List<RoleInfo> roles
    ) {
    }

    private final OrchestrationService orchestration;

    public CatalogController(OrchestrationService orchestration) {
        this.orchestration = orchestration;
    }

    @GetMapping("/api/catalog")
    public Catalog catalog() {
        List<FlowInfo> flows = orchestration.config().flows().values().stream().map(this::describe).toList();
        List<ModuleInfo> modules = orchestration.modules().values().stream()
                .map(module -> new ModuleInfo(module.name(), module.description(), module.isAvailable()))
                .toList();
        List<RoleInfo> roles = orchestration.config().roles().values().stream()
                .map(role -> new RoleInfo(role.name(), role.labelKo(), role.instructions()))
                .toList();
        return new Catalog(flows, CommandCatalog.OPTIONS, modules, roles);
    }

    private FlowInfo describe(FlowDefinition flow) {
        List<String> focus = CommandCatalog.COMMANDS.stream()
                .filter(c -> c.taskType() == flow.taskType()).findFirst()
                .map(CommandCatalog.CommandSpec::defaultFocus)
                .orElse(List.of("requirements", "risks", "verification"));
        return new FlowInfo(flow.name(), flow.label(), flow.signature(), flow.taskType().name().toLowerCase(Locale.ROOT), flow.describe(),
                flow.stages().stream().map(stage -> new StageInfo(stage.name(),
                        stage.role() == null ? "executor" : stage.role(), stage.models())).toList(),
                focus);
    }
}
