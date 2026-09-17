package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ModuleExecutionException;
import dev.orchestrator.module.ProcessRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProcessRunnerTest {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Shell one-liner for either OS. */
    private static List<String> shell(String posix, String windows) {
        return WINDOWS ? List.of("cmd.exe", "/c", windows) : List.of("sh", "-c", posix);
    }

    /** A command that blocks for ~30s on either OS. */
    private static List<String> sleep30() {
        return WINDOWS ? List.of("cmd.exe", "/c", "ping -n 31 127.0.0.1 >nul") : List.of("sleep", "30");
    }

    private static ExecutionContext context(Duration timeout, Duration idle, AtomicBoolean cancelled, List<String> summary) {
        return new ExecutionContext() {
            @Override
            public void detail(String line) {
            }

            @Override
            public void summary(String line) {
                summary.add(line);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public Duration timeout() {
                return timeout;
            }

            @Override
            public Duration idleWarning() {
                return idle;
            }
        };
    }

    @Test
    void streamsLinesAndReturnsExitCode() {
        List<String> lines = new ArrayList<>();
        int exit = ProcessRunner.run("t", shell("echo one; echo two; exit 3", "echo one& echo two& exit 3"), null, null,
                context(Duration.ofSeconds(5), Duration.ofSeconds(5), new AtomicBoolean(false), new ArrayList<>()), lines::add);

        assertEquals(3, exit);
        assertEquals(List.of("one", "two"), lines);
    }

    @Test
    void forwardsStdin() {
        List<String> lines = new ArrayList<>();
        ProcessRunner.run("t", WINDOWS ? List.of("cmd.exe", "/c", "findstr .") : List.of("cat"), null, "hello", 
                context(Duration.ofSeconds(5), Duration.ofSeconds(5), new AtomicBoolean(false), new ArrayList<>()), lines::add);

        assertEquals(List.of("hello"), lines);
    }

    @Test
    void killsOnTimeoutAndWarnsWhenIdle() {
        List<String> summary = new ArrayList<>();
        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                ProcessRunner.run("t", sleep30(), null, null,
                        context(Duration.ofMillis(1500), Duration.ofMillis(500), new AtomicBoolean(false), summary), line -> { }));

        assertEquals(ModuleExecutionException.Kind.TIMEOUT, error.kind());
        assertTrue(summary.stream().anyMatch(line -> line.contains("경고")), summary.toString());
    }

    @Test
    void killsOnCancel() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            cancelled.set(true);
        });
        canceller.start();

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                ProcessRunner.run("t", sleep30(), null, null,
                        context(Duration.ofSeconds(30), Duration.ofSeconds(30), cancelled, new ArrayList<>()), line -> { }));

        assertEquals(ModuleExecutionException.Kind.CANCELLED, error.kind());
    }

    @Test
    void detectsExecutablesOnPath() {
        assertTrue(ProcessRunner.isOnPath(WINDOWS ? "cmd" : "sh"));
        assertTrue(!ProcessRunner.isOnPath("definitely-not-a-real-binary-xyz"));
    }

    @Test
    void wrapsWindowsCmdShimsInCmdExe(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path shim = java.nio.file.Files.writeString(dir.resolve("claude.cmd"), "@echo off\r\n");
        java.nio.file.Path exe = java.nio.file.Files.writeString(dir.resolve("git.exe"), "");
        java.util.function.Function<String, java.util.Optional<java.nio.file.Path>> resolver = name ->
                name.equals("claude") ? java.util.Optional.of(shim) : name.equals("git") ? java.util.Optional.of(exe) : java.util.Optional.empty();

        assertEquals(List.of("cmd.exe", "/c", shim.toString(), "-p", "--verbose"),
                ProcessRunner.launchCommand(List.of("claude", "-p", "--verbose"), true, resolver));
        assertEquals(List.of(exe.toString(), "status"), ProcessRunner.launchCommand(List.of("git", "status"), true, resolver), ".exe runs directly");
        assertEquals(List.of("unknown", "x"), ProcessRunner.launchCommand(List.of("unknown", "x"), true, resolver), "unresolved names are passed through");
        assertEquals(List.of("claude", "-p"), ProcessRunner.launchCommand(List.of("claude", "-p"), false, resolver), "no wrapping outside Windows");

        // PATH resolution with Windows-style extensions (execute bit only exists on POSIX)
        try {
            java.nio.file.Files.setPosixFilePermissions(shim, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException windows) {
            // NTFS: every regular file is "executable" for Files.isExecutable
        }
        assertEquals(java.util.Optional.of(shim), ProcessRunner.resolveExecutable("claude", dir.toString(), List.of("", ".exe", ".cmd")));
        assertEquals(java.util.Optional.empty(), ProcessRunner.resolveExecutable("claude", dir.toString(), List.of("", ".exe")));
    }
}
