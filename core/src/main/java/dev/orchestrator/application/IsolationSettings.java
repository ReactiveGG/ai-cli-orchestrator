package dev.orchestrator.application;

import dev.orchestrator.isolation.WorkspaceIsolation;
import java.nio.file.Path;

/**
 * How competing coders are isolated.
 *
 * @param isolation     the isolation backend (null = competition mode unavailable)
 * @param workspace     the workspace agents edit (the CLI modules' working directory)
 * @param patchRoot     patches are saved under {@code <patchRoot>/<runId>/candidates/}
 * @param autoApply     apply the verifier's chosen candidate to the workspace automatically
 * @param keepWorktrees leave candidate worktrees in place after the run (debugging)
 * @param maxPatchChars diff characters per candidate included in reviewer/verifier prompts
 */
public record IsolationSettings(
        WorkspaceIsolation isolation,
        Path workspace,
        Path patchRoot,
        boolean autoApply,
        boolean keepWorktrees,
        int maxPatchChars
) {
    public static final IsolationSettings DISABLED = new IsolationSettings(null, null, null, false, false, 40_000);

    public boolean enabled() {
        return isolation != null && workspace != null;
    }
}
