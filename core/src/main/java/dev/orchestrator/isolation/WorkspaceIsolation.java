package dev.orchestrator.isolation;

import java.nio.file.Path;
import java.util.List;

/**
 * Gives each competing coder its own copy of the workspace, extracts what each
 * one changed, applies the chosen change back, and cleans up. The workspace
 * itself is never modified until {@link #apply}.
 */
public interface WorkspaceIsolation {
    /** Whether {@code workspace} can be isolated (for git: it is inside a repository). */
    boolean supports(Path workspace);

    /** Creates {@code count} candidates that all start from the workspace's current state. */
    List<Candidate> prepare(String jobId, Path workspace, int count);

    /** Captures a candidate's changes; {@code patchDir} receives {@code c<k>.patch}. */
    CandidatePatch capture(Candidate candidate, Path patchDir);

    /** Applies a captured patch to the workspace's working tree (index untouched). */
    void apply(CandidatePatch patch, Path workspace);

    /** Undoes a previously applied patch (reverse apply) so another candidate can replace it. */
    default void revert(CandidatePatch patch, Path workspace) {
        throw new UnsupportedOperationException("revert not supported");
    }

    /** Removes every worktree created for the job. Patch files are kept. */
    void cleanup(String jobId, Path workspace);
}
