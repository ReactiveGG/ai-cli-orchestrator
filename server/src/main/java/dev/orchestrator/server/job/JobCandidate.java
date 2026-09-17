package dev.orchestrator.server.job;

import dev.orchestrator.isolation.CandidatePatch;

/**
 * One competing coder's result as shown in the UI.
 *
 * @param index     1-based candidate number
 * @param agent     {@code claude/coder}-style label of the coder that produced it
 * @param stepId    the coder's step id (for linking to the diagram/log)
 * @param patchFile absolute path of the saved diff
 * @param empty     the coder changed nothing
 * @param chosen    the verifier adopted this candidate
 * @param applied   the patch has been applied to the workspace (automatically or by hand)
 */
public record JobCandidate(
        int index,
        String agent,
        String stepId,
        int filesChanged,
        int insertions,
        int deletions,
        String stat,
        String patchFile,
        boolean empty,
        boolean chosen,
        boolean applied
) {
    public static JobCandidate from(CandidatePatch patch, String agent, String stepId) {
        return new JobCandidate(patch.index(), agent, stepId, patch.filesChanged(), patch.insertions(), patch.deletions(),
                patch.stat(), patch.patchFile() == null ? null : patch.patchFile().toAbsolutePath().toString(), patch.isEmpty(), false, false);
    }

    public JobCandidate with(boolean isChosen, boolean isApplied) {
        return new JobCandidate(index, agent, stepId, filesChanged, insertions, deletions, stat, patchFile, empty, isChosen, isApplied);
    }

    public String summary() {
        return empty ? "변경 없음" : filesChanged + " files, +" + insertions + " -" + deletions;
    }
}
