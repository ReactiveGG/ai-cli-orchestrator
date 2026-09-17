package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.domain.AgentOptions;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.ModuleExecutionException;
import dev.orchestrator.domain.TaskType;
import dev.orchestrator.module.ClaudeCliModule;
import dev.orchestrator.module.CliModuleSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Replays event streams recorded from Claude Code 2.1 through a fake `claude` script. */
@DisabledOnOs(OS.WINDOWS)
class ClaudeCliModuleTest {
    @TempDir
    Path tempDir;

    private static final String SUCCESS = """
            {"type":"system","subtype":"init","cwd":"/work","model":"claude-fable-5-1","permissionMode":"default","claude_code_version":"2.1.274"}
            {"type":"rate_limit_event","rate_limit_info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.02}}}}
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"src/auth.ts"}}],"usage":{"input_tokens":2,"cache_creation_input_tokens":10376,"cache_read_input_tokens":10126,"output_tokens":40}}}
            {"type":"user","message":{"content":[{"type":"tool_result","content":"export function refresh() {}"}]}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"만료 검사가 누락되어 있습니다."}],"usage":{"input_tokens":5,"cache_creation_input_tokens":0,"cache_read_input_tokens":20502,"output_tokens":18}}}
            {"type":"result","subtype":"success","is_error":false,"result":"만료 검사가 누락되어 있습니다.","num_turns":2,"total_cost_usd":0.2112,"usage":{"input_tokens":7,"cache_creation_input_tokens":10376,"cache_read_input_tokens":30628,"output_tokens":58},"permission_denials":[{"tool_name":"Edit","tool_input":{"file_path":"src/auth.ts"}}]}
            """;

    private static final String BUDGET_ERROR = """
            {"type":"system","subtype":"init","cwd":"/work","model":"claude-fable-5-1","permissionMode":"default","claude_code_version":"2.1.274"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"OK"}],"usage":{"input_tokens":2,"cache_creation_input_tokens":10376,"cache_read_input_tokens":10126,"output_tokens":4}}}
            {"type":"result","subtype":"error_max_budget_usd","is_error":true,"errors":["Reached maximum budget ($0.1)"],"num_turns":1,"total_cost_usd":0.2112,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},"permission_denials":[]}
            """;

    private ClaudeCliModule fakeClaude(String events, String model, Double budget) throws IOException {
        Path fixture = Files.writeString(tempDir.resolve("events.jsonl"), events, StandardCharsets.UTF_8);
        Path script = tempDir.resolve("fake-claude.sh");
        // Echo the argv so the test can assert the flags, then replay the recorded stream.
        Files.writeString(script, "#!/bin/sh\necho \"{\\\"type\\\":\\\"argv\\\",\\\"argv\\\":\\\"$*\\\"}\"\ncat " + fixture + "\n");
        script.toFile().setExecutable(true);
        return new ClaudeCliModule(new CliModuleSettings(script.toString(), List.of("--max-turns", "5"), tempDir, model, budget,
                model == null ? List.of() : List.of("Bash(git status*)", "Bash(pytest*)")));
    }

    private static ExecutionContext context(List<String> summary, List<String> detail) {
        return context(summary, detail, AgentOptions.NONE);
    }

    private static ExecutionContext context(List<String> summary, List<String> detail, AgentOptions options) {
        return new ExecutionContext() {
            @Override public void detail(String line) { detail.add(line); }
            @Override public void summary(String line) { summary.add(line); }
            @Override public boolean isCancelled() { return false; }
            @Override public Duration timeout() { return Duration.ofSeconds(10); }
            @Override public Duration idleWarning() { return Duration.ofSeconds(10); }
            @Override public AgentOptions options() { return options; }
        };
    }

    private static CompiledPrompt prompt(boolean edits) {
        return new CompiledPrompt(TaskType.CUSTOM, "auth.ts", List.of(), "ko", "Task: review", edits ? "coder" : null, edits);
    }

    @Test
    void parsesSuccessStreamIntoTextUsageAndSummaries() throws IOException {
        List<String> summary = new ArrayList<>();
        List<String> detail = new ArrayList<>();
        ClaudeCliModule module = fakeClaude(SUCCESS, "sonnet", 2.0);

        ExecutionResult result = module.execute(prompt(false), new ExecutionRequest("default", "auth.ts", List.of(), "ko"), context(summary, detail));

        assertEquals("만료 검사가 누락되어 있습니다.", result.content());
        assertEquals(7 + 10376 + 30628, result.usage().inputTokens());
        assertEquals(58, result.usage().outputTokens());
        assertEquals(0.2112, result.usage().costUsd(), 1e-9);
        assertTrue(summary.stream().anyMatch(l -> l.startsWith("claude 2.1.274 · 모델 claude-fable-5-1")), summary.toString());
        assertTrue(summary.stream().anyMatch(l -> l.equals("도구 호출: Read src/auth.ts")), summary.toString());
        assertTrue(summary.stream().anyMatch(l -> l.startsWith("권한 거부됨: Edit src/auth.ts")), summary.toString());
        assertTrue(summary.stream().anyMatch(l -> l.startsWith("턴 2 · 비용 $0.2112")), summary.toString());
        String argv = detail.stream().filter(l -> l.contains("\"argv\"")).findFirst().orElseThrow();
        assertTrue(argv.contains("-p --output-format stream-json --verbose --model sonnet --max-budget-usd 2.00 --max-turns 5 --allowedTools Bash(git status*) Bash(pytest*)"), argv);
        assertTrue(!argv.contains("acceptEdits"), "read-only agents must not get acceptEdits");
    }

    @Test
    void coderGetsAcceptEditsPermissionMode() throws IOException {
        List<String> detail = new ArrayList<>();
        ClaudeCliModule module = fakeClaude(SUCCESS, null, null);

        module.execute(prompt(true), new ExecutionRequest("default", "auth.ts", List.of(), "ko"), context(new ArrayList<>(), detail));

        String argv = detail.stream().filter(l -> l.contains("\"argv\"")).findFirst().orElseThrow();
        assertTrue(argv.contains("--permission-mode acceptEdits"), argv);
        assertTrue(!argv.contains("--model") && !argv.contains("--max-budget-usd"), argv);
    }

    @Test
    void agentOptionsOverrideModelAndAddEffort() throws IOException {
        List<String> detail = new ArrayList<>();
        ClaudeCliModule module = fakeClaude(SUCCESS, "sonnet", null);

        module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(new ArrayList<>(), detail, new AgentOptions("opus", "high")));

        String argv = detail.stream().filter(l -> l.contains("\"argv\"")).findFirst().orElseThrow();
        assertTrue(argv.contains("--model opus --effort high"), argv);
        assertTrue(!argv.contains("--model sonnet"), "agent model must win over the module default");
    }

    @Test
    void budgetErrorResultFailsTheRunWithCostAndUsage() throws IOException {
        List<String> summary = new ArrayList<>();
        ClaudeCliModule module = fakeClaude(BUDGET_ERROR, null, 0.1);

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(summary, new ArrayList<>())));

        assertEquals(ModuleExecutionException.Kind.FAILED, error.kind());
        assertTrue(error.getMessage().contains("예산 상한"), error.getMessage());
        assertTrue(error.getMessage().contains("Reached maximum budget"), error.getMessage());
        assertTrue(error.getMessage().contains("$0.211"), error.getMessage());
    }
}
