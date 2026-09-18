package dev.orchestrator.module;

import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ModuleExecutionException;
import dev.orchestrator.domain.ModuleExecutionException.Kind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs a subprocess and streams its output line by line while enforcing the
 * {@link ExecutionContext} contract: cancellation, a hard timeout, and an idle
 * warning when the process goes quiet for too long.
 */
public final class ProcessRunner {
    private static final Duration POLL = Duration.ofMillis(250);
    private static final List<String> NESTED_SESSION_ENV = List.of(
            "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_SESSION_ATTENDED",
            "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_MESSAGING_TOKEN", "CLAUDE_CODE_MESSAGING_SOCKET",
            "CLAUDE_CODE_EXECPATH", "CLAUDE_PID", "CLAUDE_EFFORT");

    private ProcessRunner() {
    }

    /**
     * @param stdin text written to the process' stdin (then closed), or null
     * @return the process exit code
     */
    public static int run(
            String moduleName,
            List<String> command,
            Path workingDirectory,
            String stdin,
            ExecutionContext context,
            Consumer<String> onLine
    ) {
        ProcessBuilder builder = new ProcessBuilder(launchCommand(command)).redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        // When the orchestrator itself runs inside a Claude Code session these
        // variables would make the child CLI behave as a nested session.
        for (String key : NESTED_SESSION_ENV) {
            builder.environment().remove(key);
        }
        context.detail("$ " + String.join(" ", command));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ModuleExecutionException(Kind.FAILED, moduleName, "Cannot start " + command.get(0) + ": " + e.getMessage(), e);
        }

        AtomicReference<Instant> lastOutput = new AtomicReference<>(Instant.now());
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lastOutput.set(Instant.now());
                    onLine.accept(line);
                }
            } catch (IOException ignored) {
                // stream closed by kill; nothing more to read
            }
        }, "process-reader-" + moduleName);
        reader.setDaemon(true);
        reader.start();

        if (stdin != null) {
            try (OutputStream out = process.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The child exited before reading stdin (auth error, bad flag, ...). Its own
                // output and exit code explain why, so keep going instead of masking that.
                context.detail("(stdin not consumed: " + e.getMessage() + ")");
            }
        } else {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // nothing to send
            }
        }

        Instant started = Instant.now();
        boolean idleWarned = false;
        try {
            while (!process.waitFor(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                Instant now = Instant.now();
                if (context.isCancelled()) {
                    kill(process);
                    throw new ModuleExecutionException(Kind.CANCELLED, moduleName, moduleName + " cancelled by user");
                }
                Duration elapsed = Duration.between(started, now);
                if (elapsed.compareTo(context.timeout()) > 0) {
                    kill(process);
                    throw new ModuleExecutionException(Kind.TIMEOUT, moduleName,
                            moduleName + " exceeded timeout of " + context.timeout().toSeconds() + "s");
                }
                Duration idle = Duration.between(lastOutput.get(), now);
                if (!idleWarned && idle.compareTo(context.idleWarning()) > 0) {
                    idleWarned = true;
                    context.summary("경고: " + moduleName + " 출력이 " + idle.toSeconds() + "초 동안 없습니다");
                } else if (idleWarned && idle.compareTo(context.idleWarning()) <= 0) {
                    idleWarned = false;
                }
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new ModuleExecutionException(Kind.CANCELLED, moduleName, moduleName + " interrupted", e);
        }
        return process.exitValue();
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Finds {@code executable} on PATH (also tries .cmd/.exe on Windows). */
    public static boolean isOnPath(String executable) {
        return resolveExecutable(executable).isPresent();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Resolves an executable name to a file: absolute/relative paths as given,
     * bare names through PATH. On Windows the extensions in PATHEXT (or .exe/.cmd/.bat/.com)
     * are tried, so npm shims such as {@code claude.cmd} are found.
     */
    public static java.util.Optional<Path> resolveExecutable(String executable) {
        List<String> exts = isWindows() ? windowsExtensions() : List.of("");
        java.util.Optional<Path> onPath = resolveExecutable(executable, System.getenv("PATH"), exts);
        if (onPath.isPresent()) {
            return onPath;
        }
        // A double-clicked app or a service does not always see the PATH the user's terminal has,
        // so also look where the Claude/Codex installers and npm put their binaries.
        return resolveExecutable(executable, String.join(java.io.File.pathSeparator, wellKnownBinDirs()), exts);
    }

    /** Directories the CLI installers use, most likely first; also the "searched" list shown when nothing is found. */
    public static List<String> wellKnownBinDirs() {
        List<String> dirs = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            String localAppData = System.getenv("LOCALAPPDATA");
            String programFiles = System.getenv("ProgramFiles");
            dirs.add(home + "\\.local\\bin");                       // native installer
            if (appData != null) dirs.add(appData + "\\npm");        // npm -g
            if (localAppData != null) {
                dirs.add(localAppData + "\\Programs\\claude");
                dirs.add(localAppData + "\\Programs\\Claude Code");
                dirs.add(localAppData + "\\Programs\\nodejs");
            }
            if (programFiles != null) dirs.add(programFiles + "\\nodejs");
        } else {
            dirs.add(home + "/.local/bin");                          // native installer
            dirs.add("/usr/local/bin");
            dirs.add("/opt/homebrew/bin");
            dirs.add(home + "/.npm-global/bin");
            dirs.add(home + "/.volta/bin");
            dirs.add("/usr/bin");
            Path nvm = Path.of(home, ".nvm", "versions", "node");
            if (Files.isDirectory(nvm)) {
                try (java.util.stream.Stream<Path> versions = Files.list(nvm)) {
                    versions.sorted(java.util.Comparator.reverseOrder()).limit(3).forEach(v -> dirs.add(v.resolve("bin").toString()));
                } catch (java.io.IOException ignored) {
                    // skip
                }
            }
        }
        return dirs;
    }

    public static java.util.Optional<Path> resolveExecutable(String executable, String pathEnv, List<String> extensions) {
        if (executable == null || executable.isBlank()) {
            return java.util.Optional.empty();
        }
        if (executable.contains("/") || executable.contains("\\") || Path.of(executable).isAbsolute()) {
            for (String ext : extensions) {
                Path candidate = Path.of(executable + ext);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return java.util.Optional.of(candidate);
                }
            }
            return java.util.Optional.empty();
        }
        if (pathEnv == null) {
            return java.util.Optional.empty();
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : extensions) {
                Path candidate = Path.of(dir, executable + ext);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return java.util.Optional.of(candidate);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static List<String> windowsExtensions() {
        String pathext = System.getenv("PATHEXT");
        List<String> exts = new ArrayList<>();
        exts.add("");   // a name that already carries its extension
        if (pathext != null && !pathext.isBlank()) {
            for (String ext : pathext.split(";")) {
                if (!ext.isBlank()) {
                    exts.add(ext.trim().toLowerCase(Locale.ROOT));
                }
            }
        } else {
            exts.addAll(List.of(".exe", ".cmd", ".bat", ".com"));
        }
        return exts;
    }

    /**
     * The argv actually handed to the OS. On Windows a {@code .cmd}/{@code .bat} shim
     * (what npm installs for {@code claude}) cannot be started directly by
     * CreateProcess, so it is run through {@code cmd.exe /c}.
     */
    public static List<String> launchCommand(List<String> command) {
        return launchCommand(command, isWindows(), ProcessRunner::resolveExecutable);
    }

    public static List<String> launchCommand(List<String> command, boolean windows, java.util.function.Function<String, java.util.Optional<Path>> resolver) {
        if (command.isEmpty()) {
            return command;
        }
        if (!windows) {
            Path found = resolver.apply(command.get(0)).orElse(null);
            if (found == null || command.get(0).contains("/")) {
                return command;
            }
            List<String> launch = new ArrayList<>();
            launch.add(found.toString());
            launch.addAll(command.subList(1, command.size()));
            return launch;
        }
        Path resolved = resolver.apply(command.get(0)).orElse(null);
        if (resolved == null) {
            return command;
        }
        // Always hand the OS the resolved absolute path: a binary found in a well-known dir is not on PATH.
        String name = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> launch = new ArrayList<>();
        if (name.endsWith(".cmd") || name.endsWith(".bat")) {
            launch.add("cmd.exe");
            launch.add("/c");
        }
        launch.add(resolved.toString());
        launch.addAll(command.subList(1, command.size()));
        return launch;
    }
}
