package dev.orchestrator.isolation;

import java.nio.file.Path;

/**
 * What a coder changed in its candidate worktree, relative to the base commit.
 *
 * @param commit       commit holding the candidate's tree (== base when nothing changed)
 * @param stat         {@code git diff --stat} output
 * @param patch        full unified diff (binary-safe); empty when nothing changed
 * @param patchFile    where the diff was saved
 */
public record CandidatePatch(
        int index,
        String baseCommit,
        String commit,
        String stat,
        String patch,
        Path patchFile,
        int filesChanged,
        int insertions,
        int deletions
) {
    public boolean isEmpty() {
        return patch == null || patch.isBlank();
    }

    /** {@code 3 files, +48 -2}. */
    public String summary() {
        return isEmpty() ? "변경 없음" : filesChanged + " files, +" + insertions + " -" + deletions;
    }
}
