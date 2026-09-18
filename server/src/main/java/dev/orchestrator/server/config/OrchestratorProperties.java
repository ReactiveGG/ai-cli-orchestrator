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
        @DefaultValue("25m") Duration moduleTimeout,
        @DefaultValue("60s") Duration idleWarning,
        @DefaultValue Map<String, ModuleSettings> modules,
        @DefaultValue Status status,
        @DefaultValue Isolation isolation,
        @DefaultValue Security security,
        @DefaultValue Retention retention,
        @DefaultValue Prompt prompt
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

    /**
     * Competition mode: each competing coder gets its own git worktree.
     *
     * @param enabled       turn candidate isolation on (presets with 2+ coders need it)
     * @param linkDirs      ignored directories symlinked from the workspace into every worktree
     * @param autoApply     apply the verifier's chosen candidate to the workspace automatically
     * @param keepWorktrees keep worktrees after the run (debugging)
     * @param maxPatchChars diff characters per candidate passed to reviewers/verifier
     * @param exclude       junk patterns never captured into a candidate (in addition to .gitignore)
     */
    public record Isolation(
            @DefaultValue("true") boolean enabled,
            @DefaultValue({"node_modules", ".venv", "venv", "target", "build", ".gradle"}) List<String> linkDirs,
            @DefaultValue("true") boolean autoApply,
            @DefaultValue("false") boolean keepWorktrees,
            @DefaultValue("40000") int maxPatchChars,
            @DefaultValue({"__pycache__", "*.pyc", ".DS_Store", "Thumbs.db", "*.swp"}) List<String> exclude
    ) {
    }

    /**
     * @param allowedWorkspaceRoots the workspace (what agents may read and edit) must lie under one of these
     * @param requireToken          every /api request must present the API token (auto-generated per install)
     * @param token                 fixed token instead of the auto-generated one; empty = auto
     */
    /**
     * How much job history to keep on disk. Only finished jobs are ever pruned; the newest are kept.
     *
     * @param maxJobs finished jobs kept (0 = unlimited); the oldest beyond this are deleted with their logs
     * @param maxAge  finished jobs older than this are deleted (zero = unlimited)
     */
    public record Retention(
            @DefaultValue("200") int maxJobs,
            @DefaultValue("30d") Duration maxAge
    ) {
    }

    /**
     * Caps on earlier-stage text inlined into the next agent's prompt (see {@code PromptLimits}).
     *
     * @param maxResultChars per previous result (0 = unlimited)
     * @param maxTotalChars  across all previous results of a stage (0 = unlimited)
     */
    public record Prompt(
            @DefaultValue("24000") int maxResultChars,
            @DefaultValue("60000") int maxTotalChars
    ) {
    }

    public record Security(
            @DefaultValue("${user.home}") List<String> allowedWorkspaceRoots,
            @DefaultValue("true") boolean requireToken,
            String token
    ) {
    }

    public Path tokenFile() {
        return dataDir.resolve("api-token");
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

    public Path worktreesDir() {
        return dataDir.resolve("worktrees");
    }

    public Path workspaceOrCwd() {
        return workspace != null ? workspace : Path.of("").toAbsolutePath();
    }

    public ModuleSettings moduleSettings(String name) {
        ModuleSettings settings = modules == null ? null : modules.get(name);
        return settings != null ? settings : new ModuleSettings(ModuleMode.AUTO, name, List.of(), null, null, List.of());
    }
}
