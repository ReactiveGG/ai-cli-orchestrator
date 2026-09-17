package dev.orchestrator.module;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orchestrator.domain.AgentOptions;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.TokenUsage;
import java.util.List;

/**
 * Drives OpenAI Codex CLI: {@code codex exec --json -} with the prompt on stdin.
 * Reads the JSONL event stream ({@code item.completed} messages, {@code turn.completed} usage).
 */
public final class CodexCliModule extends CliAiModule {
    public CodexCliModule(CliModuleSettings settings) {
        super(settings);
    }

    @Override
    public String name() {
        return "codex";
    }

    @Override
    protected List<String> command(CompiledPrompt prompt, ExecutionRequest request, AgentOptions options) {
        List<String> argv = new java.util.ArrayList<>(List.of(settings.command(), "exec", "--json"));
        if (options.model() != null) {
            argv.add("--model");
            argv.add(options.model());
        }
        argv.add("-");
        return argv;
    }

    @Override
    protected TokenUsage onEvent(JsonNode event, StringBuilder output, ExecutionContext context) {
        String type = event.path("type").asText("");
        switch (type) {
            case "item.completed" -> {
                JsonNode item = event.path("item");
                String itemType = item.path("type").asText("");
                if ("agent_message".equals(itemType)) {
                    output.append(item.path("text").asText()).append('\n');
                } else if (!itemType.isEmpty()) {
                    context.summary("codex 항목 완료: " + itemType);
                }
            }
            case "turn.completed" -> {
                JsonNode usage = event.path("usage");
                return new TokenUsage(
                        longAt(usage, "input_tokens") + longAt(usage, "cached_input_tokens"),
                        longAt(usage, "output_tokens"),
                        0.0
                );
            }
            case "error" -> context.summary("codex 오류: " + event.path("message").asText(""));
            default -> {
                // other lifecycle events stay in the detail log
            }
        }
        return null;
    }
}
