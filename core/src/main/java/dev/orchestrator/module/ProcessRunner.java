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
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
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
                kill(process);
                throw new ModuleExecutionException(Kind.FAILED, moduleName, "Cannot write prompt to " + command.get(0), e);
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
        if (executable == null || executable.isBlank()) {
            return false;
        }
        Path direct = Path.of(executable);
        if (direct.isAbsolute() || executable.contains("/") || executable.contains("\\")) {
            return Files.isExecutable(direct);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> candidates = windows
                ? List.of(executable, executable + ".cmd", executable + ".exe", executable + ".bat")
                : List.of(executable);
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : candidates) {
                Path path = Path.of(dir, candidate);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return true;
                }
            }
        }
        return false;
    }
}
