package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.TaskType;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptCompilerTest {
    @Test
    void compilesStructuredPromptWithDefaultFocus() {
        PromptCompiler compiler = new PromptCompiler();

        String body = compiler.compile(new ExecutionRequest("analyze", "auth.ts", List.of(), "ko"), TaskType.ANALYZE).body();

        assertTrue(body.contains("Task: analyze"));
        assertTrue(body.contains("Target: auth.ts"));
        assertTrue(body.contains("- architecture"));
        assertTrue(body.contains("Output language: ko"));
    }
}
