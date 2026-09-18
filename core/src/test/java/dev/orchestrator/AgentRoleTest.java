package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.domain.AgentRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The built-in instructions carry the contract the orchestrator and UI rely on. */
class AgentRoleTest {
    @Test
    void reviewerAndVerifierEndWithAFixedVerdictLine() {
        assertTrue(lastLine(AgentRole.REVIEWER).contains("`판정: 승인` 또는 `판정: 수정 필요`"), lastLine(AgentRole.REVIEWER));
        assertTrue(lastLine(AgentRole.VERIFIER).contains("`판정: 승인` 또는 `판정: 반려`"), lastLine(AgentRole.VERIFIER));
        assertTrue(lastLine(AgentRole.VERIFIER).contains("`채택: 후보 N`"), "competition decision format stays in the verifier text");
    }

    @Test
    void everyStageIsToldNotToPasteFilesIntoItsOutput() {
        // Their output is inlined into the next agent's prompt (PromptLimits), so pasting is the main cost driver.
        assertTrue(AgentRole.PLANNER.instructions().contains("붙여 넣지 않는다"));
        assertTrue(AgentRole.CODER.instructions().contains("붙여 넣지 않는다"));
        assertTrue(AgentRole.REVIEWER.instructions().contains("붙여 넣지 않는다"));
        assertTrue(AgentRole.CODER.instructions().contains("실행한 테스트:"), "coder summary names the tests it ran");
        assertTrue(AgentRole.REVIEWER.instructions().contains("git diff"), "reviewer reads the real change, not the summary");
    }

    @Test
    void onlyTheCoderEditsAndTheExecutorIsPassThrough() {
        assertEquals(List.of(AgentRole.PLANNER, AgentRole.CODER, AgentRole.REVIEWER, AgentRole.VERIFIER), AgentRole.PIPELINE);
        for (AgentRole role : AgentRole.PIPELINE) {
            assertEquals("coder".equals(role.name()), role.editsFiles(), role.name());
            assertFalse(role.isPassThrough(), role.name());
            assertTrue(role.instructions().startsWith("역할: " + role.labelKo()), role.name());
        }
        assertTrue(AgentRole.EXECUTOR.isPassThrough());
    }

    private static String lastLine(AgentRole role) {
        String[] lines = role.instructions().strip().split("\\n");
        return lines[lines.length - 1].strip();
    }
}
