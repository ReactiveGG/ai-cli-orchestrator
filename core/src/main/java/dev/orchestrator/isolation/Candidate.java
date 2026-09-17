package dev.orchestrator.isolation;

import java.nio.file.Path;

/**
 * One isolated copy of the workspace for one competing coder.
 *
 * @param index      1-based candidate number
 * @param worktree   root of the git worktree
 * @param workingDir where the coder runs: the worktree, or the sub-directory that
 *                   corresponds to the workspace when the workspace is inside a repo
 * @param baseCommit commit every candidate started from
 */
public record Candidate(int index, Path worktree, Path workingDir, String baseCommit) {
}
