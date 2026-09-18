package dev.orchestrator.application;

/**
 * How much earlier-stage text may be inlined into an agent's prompt.
 *
 * <p>Why: {@code claude -p} re-sends the whole prompt on every agentic turn, so
 * long inherited text multiplies cost, and a coder that pastes whole files (or a
 * reviewer quoting a full diff) can push a 200k-token context over the edge before
 * the agent has read a single file itself. The defaults keep inherited material
 * under ~10% of that context (Korean/code text runs ~3 chars per token):
 *
 * @param maxResultChars characters kept per previous result (0 = unlimited); ~3x a normal plan or review
 * @param maxTotalChars  characters kept across all previous results of a stage (0 = unlimited)
 */
public record PromptLimits(int maxResultChars, int maxTotalChars) {
    public static final PromptLimits DEFAULT = new PromptLimits(24_000, 60_000);
    public static final PromptLimits UNLIMITED = new PromptLimits(0, 0);

    public PromptLimits {
        maxResultChars = Math.max(0, maxResultChars);
        maxTotalChars = Math.max(0, maxTotalChars);
    }

    /** Effective cap for one result given how much of the stage budget is left. */
    int capFor(int remainingTotal) {
        int per = maxResultChars > 0 ? maxResultChars : Integer.MAX_VALUE;
        int total = maxTotalChars > 0 ? remainingTotal : Integer.MAX_VALUE;
        return Math.min(per, total);
    }

    /**
     * Cuts {@code text} to {@code max} characters keeping the head (70%) and the tail (30%),
     * because conclusions and verdicts tend to sit at the end. Returns the text unchanged
     * when it fits.
     */
    static String clip(String text, int max, String what) {
        if (text == null || max <= 0 || text.length() <= max) {
            return text == null ? "" : text;
        }
        if (max < 200) {
            return text.substring(0, max) + System.lineSeparator() + "... (" + what + " " + (text.length() - max) + "자 생략)";
        }
        int head = (int) (max * 0.7);
        int tail = max - head;
        int cutAt = text.lastIndexOf('\n', head);
        if (cutAt > head / 2) {
            head = cutAt;
        }
        int resumeAt = text.indexOf('\n', text.length() - tail);
        if (resumeAt < 0 || resumeAt > text.length() - tail / 2) {
            resumeAt = text.length() - tail;
        }
        int dropped = resumeAt - head;
        return text.substring(0, head).stripTrailing()
                + System.lineSeparator() + System.lineSeparator()
                + "... (" + what + " 중간 " + dropped + "자 생략 — 전문은 작업 로그의 단계 결과 참고)"
                + System.lineSeparator() + System.lineSeparator()
                + text.substring(resumeAt).stripLeading();
    }
}
