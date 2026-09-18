package dev.orchestrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.module.ModuleMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {
    @TempDir
    Path tempDir;

    private WorkspaceService service() throws Exception {
        OrchestratorProperties properties = new OrchestratorProperties(tempDir.resolve("data"), tempDir.resolve("ws"), 2, Duration.ofMinutes(10), Duration.ofSeconds(60),
                Map.of("claude", new OrchestratorProperties.ModuleSettings(ModuleMode.STUB, "claude", List.of(), null, null, List.of())),
                new OrchestratorProperties.Status("", Duration.ofSeconds(60)),
                new OrchestratorProperties.Isolation(true, List.of(), true, false, 40_000, List.of()),
                new OrchestratorProperties.Security(List.of(tempDir.toString()), false, null),
                new OrchestratorProperties.Retention(200, Duration.ofDays(30)),
                new OrchestratorProperties.Prompt(24_000, 60_000));
        Files.createDirectories(tempDir.resolve("ws"));
        return new WorkspaceService(new RuntimeSettings(properties));
    }

    @Test
    void reportsStateAndPreparesTheFolderWithGitInit() throws Exception {
        WorkspaceService service = service();
        WorkspaceService.WorkspaceStatus before = service.status();
        assertTrue(before.exists());
        assertFalse(before.gitRepo(), "fresh folder");
        assertTrue(before.gitAvailable(), "CI and dev machines have git");
        assertNotNull(before.gitVersion());

        WorkspaceService.WorkspaceStatus after = service.gitInit();
        assertTrue(after.gitRepo());
        assertTrue(Files.isDirectory(tempDir.resolve("ws").resolve(".git")));
        assertEquals(after, service.gitInit(), "idempotent");
    }
}
