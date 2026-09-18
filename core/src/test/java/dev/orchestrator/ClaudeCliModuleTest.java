package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** A `claude` that prints {@code text} and exits with {@code code} (or kills itself when code == 137), like an unauthenticated or killed CLI. */
    private ClaudeCliModule fakeClaudeExiting(String text, int code) throws IOException {
        Path script = tempDir.resolve("fake-claude-exit.sh");
        String body = code == 137
                ? "#!/bin/sh\necho '" + text + "'\nkill -9 $$\n"
                : "#!/bin/sh\necho '" + text + "' >&2\nexit " + code + "\n";
        Files.writeString(script, body);
        script.toFile().setExecutable(true);
        return new ClaudeCliModule(new CliModuleSettings(script.toString(), List.of(), tempDir, null, null, List.of()));
    }

    /** Like fakeClaude, but also saves what arrived on stdin to {@code prompt.txt}. */
    private ClaudeCliModule fakeClaudeCapturingStdin(String events) throws IOException {
        Path fixture = Files.writeString(tempDir.resolve("events2.jsonl"), events, StandardCharsets.UTF_8);
        Path script = tempDir.resolve("fake-claude-stdin.sh");
        Files.writeString(script, "#!/bin/sh\necho \"{\\\"type\\\":\\\"argv\\\",\\\"argv\\\":\\\"$*\\\"}\"\ncat > " + tempDir.resolve("prompt.txt") + "\ncat " + fixture + "\n");
        script.toFile().setExecutable(true);
        return new ClaudeCliModule(new CliModuleSettings(script.toString(), List.of(), tempDir, null, null, List.of()));
    }

    private static final String WITH_SESSION = """
            {"type":"system","subtype":"init","session_id":"sess-42","cwd":"/work","model":"claude-sonnet-5","permissionMode":"plan","claude_code_version":"2.1.275"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"계획: greeting.py 수정"}],"usage":{"input_tokens":2,"output_tokens":4}}}
            {"type":"result","subtype":"success","is_error":false,"result":"계획: greeting.py 수정","num_turns":1,"total_cost_usd":0.01,"usage":{"input_tokens":2,"output_tokens":4},"permission_denials":[]}
            """;

    @Test
    void coderWithSlashPlanRunsPlanModeThenResumesTheSessionWithEditPermission() throws IOException {
        List<String> detail = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        ClaudeCliModule module = fakeClaudeCapturingStdin(WITH_SESSION);

        ExecutionResult result = module.execute(prompt(true), new ExecutionRequest("default", "x", List.of(), "ko"),
                context(summary, detail, new AgentOptions("sonnet", null, "/plan")));

        List<String> argvs = detail.stream().filter(l -> l.contains("\"argv\"")).toList();
        assertEquals(2, argvs.size(), "two runs: plan, then implement");
        assertTrue(argvs.get(0).contains("--permission-mode plan"), argvs.get(0));
        assertFalse(argvs.get(0).contains("acceptEdits"), "plan phase never edits: " + argvs.get(0));
        assertTrue(argvs.get(1).contains("--resume sess-42"), argvs.get(1));
        assertTrue(argvs.get(1).contains("--permission-mode acceptEdits"), argvs.get(1));
        assertFalse(argvs.get(1).contains("--permission-mode plan"), argvs.get(1));
        assertTrue(Files.readString(tempDir.resolve("prompt.txt")).startsWith("방금 세운 계획을 그대로 구현하라"), "second run tells it to execute the plan");
        assertEquals("sess-42", module.lastSessionId());
        assertTrue(result.content().startsWith("## 계획 (plan 모드)"), result.content());
        assertTrue(result.content().contains("## 구현"), result.content());
        assertEquals(8, result.usage().outputTokens(), "usage of both runs summed");
        assertTrue(summary.stream().anyMatch(l -> l.startsWith("1/2 계획")), summary.toString());
        assertTrue(summary.stream().anyMatch(l -> l.startsWith("2/2 구현: 세션 sess-42")), summary.toString());
        assertTrue(summary.stream().anyMatch(l -> l.contains("· 세션 sess-42")), "session id is logged so the user can /resume it later: " + summary);
    }

    @Test
    void resumeAndContinueBecomeCliFlagsAndNeverEnterThePrompt() throws IOException {
        List<String> detail = new ArrayList<>();
        ClaudeCliModule module = fakeClaudeCapturingStdin(SUCCESS);
        module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(new ArrayList<>(), detail, new AgentOptions(null, null, "/resume abc-123")));
        assertTrue(detail.get(0).contains("--resume abc-123"), detail.get(0));
        assertTrue(Files.readString(tempDir.resolve("prompt.txt")).startsWith("Task: review"));
        detail.clear();
        module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(new ArrayList<>(), detail, new AgentOptions(null, null, "/continue")));
        assertTrue(detail.get(0).contains("--continue"), detail.get(0));
        assertFalse(detail.get(0).contains("--resume"), detail.get(0));
        assertEquals("", new AgentOptions(null, null, "/resume").resumeSession(), "bare /resume = most recent session");
    }

    @Test
    void slashPlanBecomesPlanPermissionModeAndOtherCommandsLeadThePrompt() throws IOException {
        List<String> detail = new ArrayList<>();
        ClaudeCliModule module = fakeClaudeCapturingStdin(SUCCESS);
        module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"),
                context(new ArrayList<>(), detail, new AgentOptions("sonnet", null, "/plan")));
        String argv = detail.stream().filter(l -> l.contains("\"argv\"")).findFirst().orElse("");
        assertTrue(argv.contains("--permission-mode plan"), argv);
        assertTrue(Files.readString(tempDir.resolve("prompt.txt")).startsWith("Task: review"), "/plan is not typed into the prompt");

        module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"),
                context(new ArrayList<>(), new ArrayList<>(), new AgentOptions(null, null, "/review src/auth")));
        String stdin = Files.readString(tempDir.resolve("prompt.txt"));
        assertTrue(stdin.startsWith("/review src/auth\nTask: review"), "project command on the first line, compiled prompt after: " + stdin);
    }

    private static ExecutionContext context(List<String> summary, List<String> detail) {
        return context(summary, detail, AgentOptions.NONE);
    }

    private static final List<dev.orchestrator.domain.RateLimitInfo> RATE_LIMITS = new ArrayList<>();

    private static ExecutionContext context(List<String> summary, List<String> detail, AgentOptions options) {
        return new ExecutionContext() {
            @Override public void detail(String line) { detail.add(line); }
            @Override public void summary(String line) { summary.add(line); }
            @Override public void rateLimit(dev.orchestrator.domain.RateLimitInfo info) { RATE_LIMITS.add(info); }
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
    void rateLimitEventsReachTheContextWithBothWindows() throws IOException {
        String stream = """
                {"type":"system","subtype":"init","cwd":"/work","model":"claude-fable-5-1","permissionMode":"default","claude_code_version":"2.1.274"}
                {"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","rateLimitType":"five_hour","resetsAt":1789700000,"unifiedWindows":{"five_hour":{"utilization":0.82,"resetsAt":1789700000},"seven_day":{"utilization":0.31,"resetsAt":"2026-09-24T00:00:00Z"}}}}
                {"type":"result","subtype":"success","is_error":false,"result":"ok","num_turns":1,"total_cost_usd":0.01,"usage":{"input_tokens":1,"output_tokens":1},"permission_denials":[]}
                """;
        RATE_LIMITS.clear();
        List<String> summary = new ArrayList<>();
        ClaudeCliModule module = fakeClaude(stream, null, null);

        module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(summary, new ArrayList<>()));

        assertEquals(1, RATE_LIMITS.size());
        dev.orchestrator.domain.RateLimitInfo info = RATE_LIMITS.get(0);
        assertEquals("allowed_warning", info.status());
        assertFalse(info.allowed());
        assertEquals(0.82, info.fiveHourUtilization(), 1e-9);
        assertEquals(java.time.Instant.ofEpochSecond(1789700000L), info.fiveHourResetsAt());
        assertEquals(0.31, info.sevenDayUtilization(), 1e-9);
        assertEquals(java.time.Instant.parse("2026-09-24T00:00:00Z"), info.sevenDayResetsAt());
        assertEquals("five_hour", info.rateLimitType());
        assertTrue(summary.stream().anyMatch(l -> l.contains("rate limit allowed_warning")), summary.toString());
    }

    @Test
    void allowedRateLimitEventIsReportedWithoutAWarning() throws IOException {
        RATE_LIMITS.clear();
        List<String> summary = new ArrayList<>();
        ClaudeCliModule module = fakeClaude(SUCCESS, null, null);

        module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(summary, new ArrayList<>()));

        assertTrue(RATE_LIMITS.stream().anyMatch(i -> i.allowed() && i.fiveHourUtilization() == 0.02 && i.sevenDayUtilization() == null), RATE_LIMITS.toString());
        assertFalse(summary.stream().anyMatch(l -> l.contains("rate limit")), "allowed → no warning line: " + summary);
    }

    @Test
    void notLoggedInExitNamesTheFix() throws IOException {
        ClaudeCliModule module = fakeClaudeExiting("Not logged in · Please run /login", 1);

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(new ArrayList<>(), new ArrayList<>())));

        assertEquals(ModuleExecutionException.Kind.FAILED, error.kind());
        assertTrue(error.getMessage().contains("로그인이 필요합니다"), error.getMessage());
        assertTrue(error.getMessage().contains("/login"), error.getMessage());
        assertTrue(error.getMessage().contains("Not logged in"), "original CLI text kept: " + error.getMessage());
    }

    @Test
    void authErrorInResultEventNamesTheFix() throws IOException {
        String stream = """
                {"type":"system","subtype":"init","cwd":"/work","model":"claude-fable-5-1","permissionMode":"default","claude_code_version":"2.1.274"}
                {"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Invalid API key · Please run /login"],"num_turns":0,"total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0},"permission_denials":[]}
                """;
        ClaudeCliModule module = fakeClaude(stream, null, null);

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(new ArrayList<>(), new ArrayList<>())));

        assertTrue(error.getMessage().contains("로그인이 필요합니다"), error.getMessage());
        assertTrue(error.getMessage().contains("실행 중 오류"), error.getMessage());
    }

    @Test
    void processKilledFromOutsideIsReportedAsSuch() throws IOException {
        ClaudeCliModule module = fakeClaudeExiting("starting", 137);

        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(new ArrayList<>(), new ArrayList<>())));

        assertEquals(ModuleExecutionException.Kind.FAILED, error.kind(), "not CANCELLED: the orchestrator did not stop it");
        assertTrue(error.getMessage().contains("강제 종료"), error.getMessage());
        assertTrue(error.getMessage().contains("137"), error.getMessage());
    }

    @Test
    void ordinaryFailureShowsExitCodeAndOutputTail() throws IOException {
        ClaudeCliModule module = fakeClaudeExiting("boom: something else", 2);
        ModuleExecutionException error = assertThrows(ModuleExecutionException.class, () ->
                module.execute(prompt(false), new ExecutionRequest("default", "x", List.of(), "ko"), context(new ArrayList<>(), new ArrayList<>())));
        assertTrue(error.getMessage().contains("exited with code 2"), error.getMessage());
        assertTrue(error.getMessage().contains("boom"), error.getMessage());
        assertFalse(error.getMessage().contains("로그인"), error.getMessage());
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
