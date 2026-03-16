package dev.orchestrator;

import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptCompilerTest {
    @Test
    void compilesStructuredPromptWithDefaultFocus() {
        PromptCompiler compiler = new PromptCompiler();

        String body = compiler.compile(new ExecutionRequest(
                TaskType.ANALYZE,
                "auth.ts",
                List.of(),
                "ko"
        )).body();

        assertTrue(body.contains("Task: analyze"));
        assertTrue(body.contains("Target: auth.ts"));
        assertTrue(body.contains("- architecture"));
        assertTrue(body.contains("Output language: ko"));
    }
}
