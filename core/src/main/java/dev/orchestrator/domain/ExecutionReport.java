package dev.orchestrator.domain;

import java.util.List;
import java.util.stream.Collectors;

/** Everything produced by one orchestrated run of a request. */
public record ExecutionReport(
        ExecutionRequest request,
        FlowDefinition flow,
        CompiledPrompt prompt,
        List<ExecutionResult> results
) {
    public TokenUsage totalUsage() {
        TokenUsage total = TokenUsage.ZERO;
        for (ExecutionResult result : results) {
            total = total.plus(result.usage());
        }
        return total;
    }

    /** Distinct module names in execution order. */
    public List<String> moduleNames() {
        return results.stream().map(ExecutionResult::moduleName).distinct().toList();
    }

    /** {@code claude/planner}-style labels, one per result. */
    public List<String> labels() {
        return results.stream().map(ExecutionResult::label).toList();
    }

    /** Output of the last stage; several agents' outputs are concatenated with headers. */
    public String finalContent() {
        if (results.isEmpty()) {
            return "";
        }
        int last = results.get(results.size() - 1).stage();
        List<ExecutionResult> tail = results.stream().filter(r -> r.stage() == last).toList();
        if (tail.size() == 1) {
            return tail.get(0).content();
        }
        return tail.stream()
                .map(r -> "## " + r.label() + System.lineSeparator() + r.content())
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
    }
}
