package dev.orchestrator.domain;

import dev.orchestrator.isolation.CandidatePatch;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Everything produced by one orchestrated run of a request.
 *
 * @param candidates      patches of the competing coders (empty unless the coder stage ran N ≥ 2)
 * @param chosenCandidate candidate number the verifier adopted, or 0 when none/undecidable
 * @param applied         whether the chosen candidate was applied to the workspace
 * @param decisionNote    human-readable outcome of the decision/apply step, or null
 */
public record ExecutionReport(
        ExecutionRequest request,
        FlowDefinition flow,
        CompiledPrompt prompt,
        List<ExecutionResult> results,
        List<CandidatePatch> candidates,
        int chosenCandidate,
        boolean applied,
        String decisionNote
) {
    public ExecutionReport(ExecutionRequest request, FlowDefinition flow, CompiledPrompt prompt, List<ExecutionResult> results) {
        this(request, flow, prompt, results, List.of(), 0, false, null);
    }

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
        String body = tail.size() == 1 ? tail.get(0).content() : tail.stream()
                .map(r -> "## " + r.label() + System.lineSeparator() + r.content())
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        return decisionNote == null ? body : body + System.lineSeparator() + System.lineSeparator() + "> " + decisionNote;
    }
}
