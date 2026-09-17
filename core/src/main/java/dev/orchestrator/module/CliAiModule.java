package dev.orchestrator.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    protected abstract List<String> command(CompiledPrompt prompt, ExecutionRequest request);

    /** Interprets one JSON event; append text to {@code output}, return usage if the event carries it. */
    protected abstract TokenUsage onEvent(JsonNode event, StringBuilder output, ExecutionContext context);

    @Override
    public ExecutionResult execute(CompiledPrompt prompt, ExecutionRequest request, ExecutionContext context) {
        List<String> argv = new ArrayList<>(command(prompt, request));
        argv.addAll(settings.extraArgs());

        StringBuilder output = new StringBuilder();
        TokenUsage[] usage = { TokenUsage.ZERO };
        int exit = ProcessRunner.run(name(), argv, settings.workingDirectory(), prompt.body(), context, line -> {
            context.detail(line);
            JsonNode event = parse(line);
            if (event != null) {
                TokenUsage found = onEvent(event, output, context);
                if (found != null) {
                    usage[0] = usage[0].plus(found);
                }
            } else if (!line.isBlank()) {
                output.append(line).append('\n');
            }
        });
        if (exit != 0) {
            throw new ModuleExecutionException(Kind.FAILED, name(),
                    name() + " exited with code " + exit + (output.isEmpty() ? "" : ": " + tail(output)));
        }
        return new ExecutionResult(name(), prompt.taskType(), output.toString().trim(), usage[0]);
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
}
