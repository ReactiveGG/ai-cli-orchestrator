package dev.orchestrator.module;

import java.nio.file.Path;
import java.util.List;

/**
 * @param command          executable name or path (e.g. {@code claude})
 * @param extraArgs        appended verbatim to every invocation
 * @param workingDirectory where the CLI runs; null means the JVM's cwd
 * @param model            model alias/name passed to the CLI (e.g. {@code sonnet}), or null for the CLI default
 * @param maxBudgetUsd     per-invocation spend cap passed to the CLI, or null for none
 * @param allowedTools     tool patterns the CLI may use without asking, e.g. {@code Bash(python3 -m pytest*)};
 *                         in non-interactive mode anything else that needs permission is denied
 */
public record CliModuleSettings(String command, List<String> extraArgs, Path workingDirectory, String model, Double maxBudgetUsd, List<String> allowedTools) {
    public CliModuleSettings {
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        model = model == null || model.isBlank() ? null : model.trim();
    }

    public CliModuleSettings(String command, List<String> extraArgs, Path workingDirectory) {
        this(command, extraArgs, workingDirectory, null, null, List.of());
    }

    public CliModuleSettings(String command, List<String> extraArgs, Path workingDirectory, String model, Double maxBudgetUsd) {
        this(command, extraArgs, workingDirectory, model, maxBudgetUsd, List.of());
    }

    public static CliModuleSettings of(String command) {
        return new CliModuleSettings(command, List.of(), null, null, null, List.of());
    }
}
