package dev.orchestrator.server.job;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.ExecutionRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * What the web UI posts: a structured request ({@code target} + optional {@code flow} = preset)
 * or a raw {@code commandLine} such as {@code "\"jwt refresh\" --preset cross-review --focus security"}.
 * A {@code flow} given alongside {@code commandLine} overrides the line's {@code --preset}.
 */
public record JobRequest(
        String commandLine,
        String flow,
        String target,
        List<String> focus,
        String language
) {
    public ExecutionRequest toExecutionRequest() {
        if (commandLine != null && !commandLine.isBlank()) {
            ExecutionRequest parsed = CommandLineParser.parse(commandLine);
            return flow == null || flow.isBlank() ? parsed
                    : new ExecutionRequest(flow, parsed.target(), parsed.focus(), parsed.responseLanguage());
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target is required");
        }
        return new ExecutionRequest(flow == null || flow.isBlank() ? FlowConfig.DEFAULT_PRESET : flow, target.strip(), focus, language);
    }

    /** Display form, e.g. {@code "jwt refresh" --preset cross-review --focus security}. */
    public static String display(ExecutionRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.target().contains(" ") ? "\"" + request.target() + "\"" : request.target());
        if (!FlowConfig.DEFAULT_PRESET.equals(request.flow())) {
            parts.add("--preset");
            parts.add(request.flow());
        }
        request.focus().forEach(item -> {
            parts.add("--focus");
            parts.add(item);
        });
        if (!"ko".equals(request.responseLanguage())) {
            parts.add("--language");
            parts.add(request.responseLanguage());
        }
        return String.join(" ", parts);
    }
}
