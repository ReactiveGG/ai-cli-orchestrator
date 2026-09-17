package dev.orchestrator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.module.ModuleMode;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class OrchestratorCommandTest {
    private static String run(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(OrchestratorCommand.create(ModuleMode.STUB));
        commandLine.setOut(new PrintWriter(output, true, StandardCharsets.UTF_8));
        assertEquals(0, commandLine.execute(args));
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void runUsesDefaultPreset() {
        String text = run("run", "auth.ts", "--focus", "security");
        assertTrue(text.contains("Flow: default"));
        assertTrue(text.contains("Modules: claude/planner, claude/coder, claude/reviewer, claude/verifier"));
        assertTrue(text.contains("- security"));
    }

    @Test
    void runWithCrossReviewPresetRunsTwoReviewers() {
        String text = run("run", "loginService.ts", "--preset", "cross-review");
        assertTrue(text.contains("Modules: claude/planner, claude/coder, claude/reviewer, claude/reviewer, claude/verifier"));
    }

    @Test
    void presetsCommandListsSignatures() {
        String text = run("presets");
        assertTrue(text.contains("default"));
        assertTrue(text.contains("1-1-1-1"));
        assertTrue(text.contains("1-1-2-1"));
        assertTrue(text.contains("1-3-3-1"));
    }
}
