package dev.orchestrator;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorCommandTest {
    @Test
    void routesAnalyzeToCodex() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(OrchestratorCommand.createDefault());
        commandLine.setOut(new PrintWriter(output, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute("analyze", "auth.ts");

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(text.contains("Modules: codex"));
        assertTrue(text.contains("[codex]"));
    }

    @Test
    void routesVerifyToBothModules() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(OrchestratorCommand.createDefault());
        commandLine.setOut(new PrintWriter(output, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute("verify", "loginService.ts");

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(text.contains("Modules: codex, claude"));
        assertTrue(text.contains("[codex]"));
        assertTrue(text.contains("[claude]"));
    }
}
