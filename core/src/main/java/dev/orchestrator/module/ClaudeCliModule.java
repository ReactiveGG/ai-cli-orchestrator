package dev.orchestrator.module;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionContext;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.TokenUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives Claude Code in non-interactive mode:
 * {@code claude -p --output-format stream-json --verbose} with the prompt on stdin.
 * Implement tasks run with {@code --permission-mode acceptEdits} so file edits go through.
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
    protected List<String> command(CompiledPrompt prompt, ExecutionRequest request) {
        List<String> argv = new ArrayList<>(List.of(
                settings.command(), "-p", "--output-format", "stream-json", "--verbose"
        ));
        if (prompt.editsFiles()) {
            argv.add("--permission-mode");
            argv.add("acceptEdits");
        }
        return argv;
    }

    @Override
    protected TokenUsage onEvent(JsonNode event, StringBuilder output, ExecutionContext context) {
        String type = event.path("type").asText("");
        switch (type) {
            case "assistant" -> {
                for (JsonNode block : event.path("message").path("content")) {
                    String blockType = block.path("type").asText("");
                    if ("text".equals(blockType)) {
                        output.append(block.path("text").asText()).append('\n');
                    } else if ("tool_use".equals(blockType)) {
                        context.summary("도구 호출: " + block.path("name").asText("?"));
                    }
                }
            }
            case "result" -> {
                if (output.isEmpty() && event.hasNonNull("result")) {
                    output.append(event.path("result").asText());
                }
                JsonNode usage = event.path("usage");
                return new TokenUsage(
                        longAt(usage, "input_tokens") + longAt(usage, "cache_read_input_tokens") + longAt(usage, "cache_creation_input_tokens"),
                        longAt(usage, "output_tokens"),
                        event.path("total_cost_usd").asDouble(0.0)
                );
            }
            default -> {
                // system/user events are kept in the detail log only
            }
        }
        return null;
    }
}
