package dev.orchestrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.module.ModuleMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeSettingsTest {
    @TempDir
    Path tempDir;

    private OrchestratorProperties properties() {
        return new OrchestratorProperties(tempDir, tempDir, 2, Duration.ofMinutes(10), Duration.ofSeconds(60),
                Map.of("claude", new OrchestratorProperties.ModuleSettings(ModuleMode.AUTO, "claude", List.of(), null, 2.0, List.of("Bash(git status*)"))),
                new OrchestratorProperties.Status("", Duration.ofSeconds(60)),
                new OrchestratorProperties.Isolation(true, List.of("node_modules"), true, false, 40_000, List.of("__pycache__")),
                new OrchestratorProperties.Security(List.of(tempDir.toString()), false, null),
                new OrchestratorProperties.Retention(200, Duration.ofDays(30)),
                new OrchestratorProperties.Prompt(24_000, 60_000));
    }

    @Test
    void startsFromPropertiesThenPersistsAndReloadsOverrides() throws Exception {
        RuntimeSettings settings = new RuntimeSettings(properties());
        assertEquals(2, settings.current().concurrency());
        assertEquals("claude", settings.module("claude").command());
        assertEquals(2.0, settings.module("claude").maxBudgetUsd());
        assertNull(settings.module("claude").model());

        Path ws = Files.createDirectories(tempDir.resolve("other-ws"));
        RuntimeSettings.Snapshot next = new RuntimeSettings.Snapshot(ws.toString(), 3, 300, 45,
                Map.of("claude", new RuntimeSettings.ModuleSnapshot(ModuleMode.CLI, "claude", "sonnet", 1.5, List.of("Bash(pytest*)", ""), List.of())),
                new RuntimeSettings.IsolationSnapshot(true, false, true, 20_000, List.of("node_modules"), List.of("*.pyc")));
        settings.update(next);

        assertTrue(Files.isRegularFile(tempDir.resolve("settings.yml")));
        RuntimeSettings reloaded = new RuntimeSettings(properties());
        assertEquals(ws.toString(), reloaded.current().workspace());
        assertEquals(3, reloaded.current().concurrency());
        assertEquals(Duration.ofSeconds(300), reloaded.moduleTimeout());
        assertEquals(Duration.ofSeconds(45), reloaded.idleWarning());
        assertEquals(ModuleMode.CLI, reloaded.module("claude").mode());
        assertEquals("sonnet", reloaded.module("claude").model());
        assertEquals(1.5, reloaded.module("claude").maxBudgetUsd());
        assertEquals(List.of("Bash(pytest*)"), reloaded.module("claude").allowedTools(), "blank entries dropped");
        assertEquals("codex", reloaded.module("codex").command(), "untouched module keeps defaults");
        assertTrue(!reloaded.isolation().autoApply());
        assertTrue(reloaded.isolation().keepWorktrees());
        assertEquals(List.of("*.pyc"), reloaded.isolation().exclude());
    }

    @Test
    void workspaceMustStayUnderAllowedRoots() throws Exception {
        RuntimeSettings settings = new RuntimeSettings(properties());
        RuntimeSettings.Snapshot base = settings.current();
        Path outside = Files.createTempDirectory("outside-root");
        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> settings.update(new RuntimeSettings.Snapshot(outside.toString(), 2, 600, 60, base.modules(), base.isolation())));
            assertTrue(error.getMessage().contains("허용된 루트"), error.getMessage());
            Path inside = Files.createDirectories(tempDir.resolve("proj"));
            assertEquals(inside.toString(), settings.update(new RuntimeSettings.Snapshot(inside.toString(), 2, 600, 60, base.modules(), base.isolation())).workspace());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsBadValues() {
        RuntimeSettings settings = new RuntimeSettings(properties());
        RuntimeSettings.Snapshot base = settings.current();
        assertThrows(IllegalArgumentException.class, () -> settings.update(new RuntimeSettings.Snapshot(tempDir.resolve("missing").toString(), 2, 600, 60, base.modules(), base.isolation())));
        assertThrows(IllegalArgumentException.class, () -> settings.update(new RuntimeSettings.Snapshot(base.workspace(), 0, 600, 60, base.modules(), base.isolation())));
        assertThrows(IllegalArgumentException.class, () -> settings.update(new RuntimeSettings.Snapshot(base.workspace(), 2, 10, 60, base.modules(), base.isolation())));
        assertEquals(2, settings.current().concurrency(), "failed update leaves settings unchanged");
    }
}
