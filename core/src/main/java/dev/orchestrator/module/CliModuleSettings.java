package dev.orchestrator.module;

import java.nio.file.Path;
import java.util.List;

/**
 * @param command          executable name or path (e.g. {@code claude})
 * @param extraArgs        appended verbatim to every invocation
 * @param workingDirectory where the CLI runs; null means the JVM's cwd
 */
public record CliModuleSettings(String command, List<String> extraArgs, Path workingDirectory) {
    public CliModuleSettings {
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
    }

    public static CliModuleSettings of(String command) {
        return new CliModuleSettings(command, List.of(), null);
    }
}
