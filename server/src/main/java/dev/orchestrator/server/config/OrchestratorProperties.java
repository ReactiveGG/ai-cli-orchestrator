package dev.orchestrator.server.config;

import dev.orchestrator.module.ModuleMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code orchestrator.*} settings (see application.yml).
 *
 * @param dataDir      where jobs, logs and the routing file live
 * @param workspace    directory the AI CLIs run in (the code they may edit)
 * @param concurrency  how many jobs run at once; the rest wait in the queue
 * @param moduleTimeout hard limit per module execution
 * @param idleWarning  silence before a "no output" warning is logged
 * @param modules      per-module CLI settings keyed by module name
 */
@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(
        @DefaultValue("${user.home}/.ai-orchestrator") Path dataDir,
        Path workspace,
        @DefaultValue("2") int concurrency,
        @DefaultValue("10m") Duration moduleTimeout,
        @DefaultValue("60s") Duration idleWarning,
        @DefaultValue Map<String, ModuleSettings> modules,
        @DefaultValue Status status
) {
    /**
     * @param model        model alias/name passed to the CLI ({@code --model}), null = CLI default
     * @param maxBudgetUsd spend cap per agent run ({@code --max-budget-usd}), null = none
     * @param allowedTools tool patterns allowed without a prompt ({@code --allowedTools}); everything else
     *                     that needs permission is denied in non-interactive mode
     */
    public record ModuleSettings(
            @DefaultValue("AUTO") ModuleMode mode,
            String command,
            @DefaultValue List<String> extraArgs,
            String model,
            Double maxBudgetUsd,
            @DefaultValue List<String> allowedTools
    ) {
    }

    public record Status(
            @DefaultValue("https://status.claude.com/api/v2/status.json") String anthropicStatusUrl,
            @DefaultValue("60s") Duration cacheTtl
    ) {
    }

    public Path routingFile() {
        return dataDir.resolve("orchestrator.yml");
    }

    public Path jobsDir() {
        return dataDir.resolve("jobs");
    }

    public Path workspaceOrCwd() {
        return workspace != null ? workspace : Path.of("").toAbsolutePath();
    }

    public ModuleSettings moduleSettings(String name) {
        ModuleSettings settings = modules == null ? null : modules.get(name);
        return settings != null ? settings : new ModuleSettings(ModuleMode.AUTO, name, List.of(), null, null, List.of());
    }
}
