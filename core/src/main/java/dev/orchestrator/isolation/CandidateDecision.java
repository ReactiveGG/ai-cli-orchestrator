package dev.orchestrator.isolation;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the verifier's final decision, e.g. {@code 채택: 후보 2}, {@code 채택 후보: 2},
 * {@code ADOPT: candidate 2}. The last match in the text wins so a restated
 * decision at the end overrides earlier discussion.
 */
public final class CandidateDecision {
    private static final Pattern KO = Pattern.compile("채택\\s*(?:후보)?\\s*[:：]\\s*(?:후보)?\\s*(\\d+)");
    private static final Pattern EN = Pattern.compile("(?i)ADOPT(?:ED)?\\s*[:：]\\s*(?:candidate)?\\s*#?(\\d+)");

    private CandidateDecision() {
    }

    public static Optional<Integer> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Integer last = null;
        for (Pattern pattern : new Pattern[] {KO, EN}) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                last = Integer.parseInt(m.group(1));
            }
            if (last != null) {
                return Optional.of(last);
            }
        }
        return Optional.empty();
    }
}
