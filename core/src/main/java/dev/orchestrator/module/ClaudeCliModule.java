package dev.orchestrator.module;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orchestrator.domain.AgentOptions;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives Claude Code in non-interactive mode:
 * {@code claude -p --output-format stream-json --verbose} with the prompt on stdin.
 *
 * <p>Event stream (verified against Claude Code 2.1): {@code system} (subtype
 * {@code init} carries model, cwd, permissionMode), {@code rate_limit_event},
 * {@code assistant} (message.content blocks, message.usage per turn) and one
 * final {@code result} ({@code subtype} success or {@code error_*},
 * {@code is_error}, {@code result} text, {@code usage}, {@code total_cost_usd},
 * {@code modelUsage}, {@code permission_denials}, {@code errors}).
 *
 * <p>Agents that may edit files run with {@code --permission-mode acceptEdits};
 * every other agent keeps the default mode, where edits are denied and reads
 * are allowed. Non-interactive runs cannot answer permission prompts, so shell
 * commands are denied unless they match {@link CliModuleSettings#allowedTools()}
 * (passed as {@code --allowedTools}, which is variadic and therefore goes last).
 */
public final class ClaudeCliModule extends CliAiModule {
    public ClaudeCliModule(CliModuleSettings settings) {
        super(settings);
    }

    @Override
    public String name() {
        return "claude";
    }

    @Override
    protected List<String> command(CompiledPrompt prompt, ExecutionRequest request, AgentOptions options) {
        pendingUsage = TokenUsage.ZERO;   // command() runs once per execution, before any event
        List<String> argv = new ArrayList<>(List.of(
                settings.command(), "-p", "--output-format", "stream-json", "--verbose"
        ));
        String resume = options.resumeSession();
        if (resume != null) {
            if (resume.isEmpty()) {
                argv.add("--continue");           // /continue or bare /resume: most recent session in this workspace
            } else {
                argv.add("--resume");             // /resume <session-id>
                argv.add(resume);
            }
        }
        if (options.planMode()) {
            argv.add("--permission-mode");   // like /plan in the interactive CLI: read and plan, never edit
            argv.add("plan");
        } else if (prompt.editsFiles()) {
            argv.add("--permission-mode");
            argv.add("acceptEdits");
        }
        String model = options.model() != null ? options.model() : settings.model();
        if (model != null) {
            argv.add("--model");
            argv.add(model);
        }
        if (options.effort() != null) {
            argv.add("--effort");
            argv.add(options.effort());
        }
        if (settings.maxBudgetUsd() != null && settings.maxBudgetUsd() > 0) {
            argv.add("--max-budget-usd");
            argv.add(String.format(Locale.ROOT, "%.2f", settings.maxBudgetUsd()));
        }
        return argv;
    }

    @Override
    protected List<String> trailingArgs() {
        if (settings.allowedTools().isEmpty()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        args.add("--allowedTools");
        args.addAll(settings.allowedTools());
        return args;
    }

    @Override
    protected TokenUsage onEvent(JsonNode event, StringBuilder output, ExecutionContext context) {
        String type = event.path("type").asText("");
        switch (type) {
            case "system" -> {
                if ("init".equals(event.path("subtype").asText(""))) {
                    sessionId = event.hasNonNull("session_id") ? event.path("session_id").asText() : sessionId;
                    context.summary("claude " + event.path("claude_code_version").asText("?")
                            + " · 모델 " + event.path("model").asText("?")
                            + " · 권한 " + event.path("permissionMode").asText("default")
                            + (sessionId == null ? "" : " · 세션 " + sessionId)
                            + " · cwd " + event.path("cwd").asText("?"));
                }
            }
            case "assistant" -> {
                for (JsonNode block : event.path("message").path("content")) {
                    String blockType = block.path("type").asText("");
                    if ("text".equals(blockType)) {
                        output.append(block.path("text").asText()).append('\n');
                    } else if ("tool_use".equals(blockType)) {
                        context.summary("도구 호출: " + block.path("name").asText("?") + describeToolInput(block.path("input")));
                    }
                }
                // Per-turn usage is the fallback when the result event carries none (error results do).
                pendingUsage = pendingUsage.plus(anthropicUsage(event.path("message").path("usage"), 0.0));
            }
            case "rate_limit_event" -> {
                JsonNode info = event.path("rate_limit_info");
                String status = info.path("status").asText("");
                context.rateLimit(parseRateLimit(info));
                if (!status.isEmpty() && !"allowed".equals(status)) {
                    context.summary("경고: rate limit " + status + (info.hasNonNull("resetsAt") ? " (해제 " + info.path("resetsAt").asText() + ")" : ""));
                }
            }
            case "result" -> {
                String subtype = event.path("subtype").asText("");
                double cost = event.path("total_cost_usd").asDouble(0.0);
                TokenUsage usage = anthropicUsage(event.path("usage"), cost);
                if (usage.totalTokens() == 0) {
                    usage = new TokenUsage(pendingUsage.inputTokens(), pendingUsage.outputTokens(), cost);
                }
                for (JsonNode denial : event.path("permission_denials")) {
                    context.summary("권한 거부됨: " + denial.path("tool_name").asText("?") + describeToolInput(denial.path("tool_input")));
                }
                if (event.path("is_error").asBoolean(false) || (!subtype.isEmpty() && !"success".equals(subtype))) {
                    List<String> errors = new ArrayList<>();
                    event.path("errors").forEach(e -> errors.add(e.asText()));
                    throw new EventFailure(describeError(subtype) + (errors.isEmpty() ? "" : " – " + String.join("; ", errors))
                            + String.format(Locale.ROOT, " (비용 $%.3f)", cost));
                }
                if (output.isEmpty() && event.hasNonNull("result")) {
                    output.append(event.path("result").asText());
                }
                context.summary(String.format(Locale.ROOT, "턴 %d · 비용 $%.4f", event.path("num_turns").asInt(0), cost));
                return usage;
            }
            default -> {
                // user/tool_result events stay in the detail log only
            }
        }
        return null;
    }

    private TokenUsage pendingUsage = TokenUsage.ZERO;
    /** {@code session_id} of the last run (from the init event); what {@code /resume <id>} needs. */
    private volatile String sessionId;

    public String lastSessionId() {
        return sessionId;
    }

    /**
     * A coder with {@code /plan} gets the interactive flow in two runs, because non-interactive plan mode
     * has nobody to approve the plan: first plan mode (read-only), then the same session resumed with
     * edit permission and told to carry the plan out. Other agents run once as configured.
     */
    @Override
    public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
        AgentOptions options = context.options();
        if (!(options.planMode() && prompt.editsFiles())) {
            return super.execute(prompt, request, context);
        }
        context.summary("1/2 계획: /plan (permission plan) — 읽기만 하고 계획을 세운다");
        sessionId = null;
        ExecutionResult plan = super.execute(prompt, request, context);
        String id = sessionId;
        if (id == null || id.isBlank()) {
            throw new dev.orchestrator.domain.ModuleExecutionException(dev.orchestrator.domain.ModuleExecutionException.Kind.FAILED, name(),
                    name() + ": 계획 세션 id를 받지 못해 이어서 구현할 수 없습니다 (init 이벤트에 session_id 없음)");
        }
        context.summary("2/2 구현: 세션 " + id + " 이어받아 acceptEdits로 계획을 실행한다");
        AgentOptions phase2 = new AgentOptions(options.model(), options.effort(), "/resume " + id);
        ExecutionContext resumed = new ExecutionContext() {
            @Override public void detail(String line) { context.detail(line); }
            @Override public void summary(String line) { context.summary(line); }
            @Override public boolean isCancelled() { return context.isCancelled(); }
            @Override public java.time.Duration timeout() { return context.timeout(); }
            @Override public java.time.Duration idleWarning() { return context.idleWarning(); }
            @Override public AgentOptions options() { return phase2; }
            @Override public java.nio.file.Path workingDirectory() { return context.workingDirectory(); }
            @Override public void rateLimit(dev.orchestrator.domain.RateLimitInfo info) { context.rateLimit(info); }
        };
        CompiledPrompt go = new CompiledPrompt(prompt.taskType(), prompt.target(), prompt.focus(), prompt.responseLanguage(),
                "방금 세운 계획을 그대로 구현하라. 계획에서 벗어나지 말고, 허용된 테스트를 실행해 통과시킨 뒤 "
                        + "'변경 파일 / 실행한 테스트 / 계획과 달라진 점 / 남은 일' 형식으로만 요약하라.",
                prompt.role(), true);
        ExecutionResult impl = super.execute(go, request, resumed);
        String content = "## 계획 (plan 모드)\n" + plan.content().strip() + "\n\n## 구현\n" + impl.content().strip();
        return new ExecutionResult(name(), prompt.taskType(), content, plan.usage().plus(impl.usage()));
    }

    private static String describeToolInput(JsonNode input) {
        if (input == null || input.isMissingNode()) {
            return "";
        }
        for (String key : List.of("file_path", "command", "pattern", "path")) {
            if (input.hasNonNull(key)) {
                String value = input.path(key).asText();
                return " " + (value.length() > 80 ? value.substring(0, 80) + "…" : value);
            }
        }
        return "";
    }

    /** {@code rate_limit_info}: status, rateLimitType, resetsAt and unifiedWindows.{five_hour,seven_day}.{utilization,resetsAt}. */
    static dev.orchestrator.domain.RateLimitInfo parseRateLimit(JsonNode info) {
        JsonNode windows = info.path("unifiedWindows");
        JsonNode five = windows.path("five_hour");
        JsonNode seven = windows.path("seven_day");
        java.time.Instant fallbackReset = instantOf(info.get("resetsAt"));
        return new dev.orchestrator.domain.RateLimitInfo(
                info.path("status").asText(null),
                five.hasNonNull("utilization") ? five.path("utilization").asDouble() : null,
                five.hasNonNull("resetsAt") ? instantOf(five.get("resetsAt")) : fallbackReset,
                seven.hasNonNull("utilization") ? seven.path("utilization").asDouble() : null,
                seven.hasNonNull("resetsAt") ? instantOf(seven.get("resetsAt")) : null,
                info.hasNonNull("rateLimitType") ? info.path("rateLimitType").asText() : null,
                java.time.Instant.now(),
                info.hasNonNull("isUsingOverage") ? info.path("isUsingOverage").asBoolean() : null);
    }

    /** Accepts epoch seconds, epoch millis or ISO-8601 text. */
    static java.time.Instant instantOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                long v = node.asLong();
                return v > 100_000_000_000L ? java.time.Instant.ofEpochMilli(v) : java.time.Instant.ofEpochSecond(v);
            }
            return java.time.Instant.parse(node.asText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String describeError(String subtype) {
        return switch (subtype) {
            case "error_max_budget_usd" -> "예산 상한(--max-budget-usd) 초과로 중단됨";
            case "error_max_turns" -> "턴 수 상한 초과로 중단됨";
            case "error_during_execution" -> "실행 중 오류";
            default -> "claude 실패 (" + subtype + ")";
        };
    }
}
