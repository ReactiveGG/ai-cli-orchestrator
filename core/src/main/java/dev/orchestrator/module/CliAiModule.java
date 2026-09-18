package dev.orchestrator.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orchestrator.domain.AgentOptions;
import dev.orchestrator.domain.AiModule;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.ModuleExecutionException;
import dev.orchestrator.domain.ModuleExecutionException.Kind;
import dev.orchestrator.domain.TokenUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * Base for modules that shell out to a coding-agent CLI emitting JSON lines.
 * Subclasses build the command line and interpret each JSON event.
 */
public abstract class CliAiModule implements AiModule {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final CliModuleSettings settings;

    protected CliAiModule(CliModuleSettings settings) {
        this.settings = settings;
    }

    @Override
    public String description() {
        return "cli: " + settings.command();
    }

    @Override
    public boolean isAvailable() {
        return ProcessRunner.isOnPath(settings.command());
    }

    /** Full argv, without the prompt (the prompt is written to stdin). */
    protected abstract List<String> command(CompiledPrompt prompt, ExecutionRequest request, AgentOptions options);

    /** Arguments that must come last (variadic flags such as {@code --allowedTools}). */
    protected List<String> trailingArgs() {
        return List.of();
    }

    /** Interprets one JSON event; append text to {@code output}, return usage if the event carries it. */
    protected abstract TokenUsage onEvent(JsonNode event, StringBuilder output, ExecutionContext context);

    @Override
    public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
        List<String> argv = new ArrayList<>(command(prompt, request, context.options()));
        argv.addAll(settings.extraArgs());
        argv.addAll(trailingArgs());

        StringBuilder output = new StringBuilder();
        TokenUsage[] usage = { TokenUsage.ZERO };
        String[] failure = { null };
        java.nio.file.Path cwd = context.workingDirectory() != null ? context.workingDirectory() : settings.workingDirectory();
        int exit = ProcessRunner.run(name(), argv, cwd, promptText(prompt, context.options()), context, line -> {
            context.detail(line);
            JsonNode event = parse(line);
            if (event != null) {
                try {
                    TokenUsage found = onEvent(event, output, context);
                    if (found != null) {
                        usage[0] = usage[0].plus(found);
                    }
                } catch (EventFailure e) {
                    failure[0] = e.getMessage();
                }
            } else if (!line.isBlank()) {
                output.append(line).append('\n');
            }
        });
        if (failure[0] != null) {
            String hint = loginHint(failure[0]);
            throw new ModuleExecutionException(Kind.FAILED, name(), name() + ": " + (hint == null ? "" : hint + " – ") + failure[0]);
        }
        if (exit != 0) {
            throw new ModuleExecutionException(Kind.FAILED, name(), describeExit(exit, output.toString()));
        }
        return new ExecutionResult(name(), prompt.taskType(), output.toString().trim(), usage[0]);
    }

    /**
     * Turns a non-zero exit into a message a user can act on: a login problem names the fix,
     * a signal death (the server did not kill it: no cancel/timeout came through here) says so,
     * anything else shows the exit code and the tail of the output.
     */
    String describeExit(int exit, String output) {
        String hint = loginHint(output);
        if (hint != null) {
            return name() + ": " + hint + " (종료 코드 " + exit + (output.isBlank() ? "" : ", CLI 출력: " + tail(new StringBuilder(output))) + ")";
        }
        if (exit == 137 || exit == 143 || exit == 130 || exit == -1 || (exit > 128 && output.isBlank())) {
            String signal = switch (exit) { case 137 -> "SIGKILL"; case 143 -> "SIGTERM"; case 130 -> "SIGINT"; default -> "signal"; };
            return name() + " 프로세스가 외부에서 강제 종료됨 (종료 코드 " + exit + ", " + signal + ") – 작업 관리자나 다른 셸에서 죽였거나 메모리 부족일 수 있음"
                    + (output.isBlank() ? "" : ": " + tail(new StringBuilder(output)));
        }
        return name() + " exited with code " + exit + (output.isBlank() ? "" : ": " + tail(new StringBuilder(output)));
    }

    /** Recognises "not logged in" style output from the CLI and returns the fix, or null. */
    protected static String loginHint(String text) {
        if (text == null) {
            return null;
        }
        String t = text.toLowerCase(java.util.Locale.ROOT);
        boolean auth = t.contains("not logged in") || t.contains("please run /login") || t.contains("please log in")
                || t.contains("claude login") || t.contains("invalid api key") || t.contains("authentication_error")
                || t.contains("oauth token") || t.contains("token has expired") || t.contains("401 unauthorized")
                || t.contains("\"unauthorized\"") || t.contains("not authenticated");
        return auth ? "Claude CLI 로그인이 필요합니다 – 터미널에서 `claude`를 실행해 /login(또는 `claude auth login`) 하세요. 서버는 로그인 화면을 띄울 수 없습니다" : null;
    }

    /**
     * The text sent on stdin. A per-agent slash command ({@code /review}, {@code /spec …}) goes on the
     * first line so the CLI runs that project command or skill with the compiled prompt as its input.
     */
    protected String promptText(CompiledPrompt prompt, AgentOptions options) {
        String command = options == null ? null : options.promptCommand();
        return command == null ? prompt.body() : command + "\n" + prompt.body();
    }

    /** Thrown by {@link #onEvent} when the CLI reported a terminal error; the run fails with this message. */
    protected static final class EventFailure extends RuntimeException {
        public EventFailure(String message) {
            super(message);
        }
    }

    private static JsonNode parse(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static String tail(StringBuilder text) {
        String s = text.toString().trim();
        return s.length() > 300 ? "..." + s.substring(s.length() - 300) : s;
    }

    protected static long longAt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? 0 : value.asLong();
    }

    /** Anthropic-style usage: prompt tokens = fresh input + cache writes + cache reads. */
    protected static TokenUsage anthropicUsage(JsonNode usage, double costUsd) {
        return new TokenUsage(
                longAt(usage, "input_tokens") + longAt(usage, "cache_creation_input_tokens") + longAt(usage, "cache_read_input_tokens"),
                longAt(usage, "output_tokens"),
                costUsd);
    }
}
