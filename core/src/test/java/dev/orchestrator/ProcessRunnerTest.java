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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {
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
        int exit = ProcessRunner.run("t", List.of("sh", "-c", "echo one; echo two; exit 3"), null, null,
                context(Duration.ofSeconds(5), Duration.ofSeconds(5), new AtomicBoolean(false), new ArrayList<>()), lines::add);

        assertEquals(3, exit);
        assertEquals(List.of("one", "two"), lines);
    }

    @Test
    void forwardsStdin() {
        List<String> lines = new ArrayList<>();
        ProcessRunner.run("t", List.of("cat"), null, "hello", 
                context(Duration.ofSeconds(5), Duration.ofSeconds(5), new AtomicBoolean(false), new ArrayList<>()), lines::add);

        assertEquals(List.of("hello"), lines);
    }

    @Test
    void killsOnTimeoutAndWarnsWhenIdle() {
        List<String> summary = new ArrayList<>();
        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                ProcessRunner.run("t", List.of("sleep", "30"), null, null,
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
                ProcessRunner.run("t", List.of("sleep", "30"), null, null,
                        context(Duration.ofSeconds(30), Duration.ofSeconds(30), cancelled, new ArrayList<>()), line -> { }));

        assertEquals(ModuleExecutionException.Kind.CANCELLED, error.kind());
    }

    @Test
    void detectsExecutablesOnPath() {
        assertTrue(ProcessRunner.isOnPath("sh"));
        assertTrue(!ProcessRunner.isOnPath("definitely-not-a-real-binary-xyz"));
    }
}
