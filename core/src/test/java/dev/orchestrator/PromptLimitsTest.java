package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.application.PromptCompiler;
import dev.orchestrator.application.PromptLimits;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.domain.TaskType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptLimitsTest {
    private static String lines(String prefix, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append(prefix).append(' ').append(i).append(": 0123456789 0123456789 0123456789\n");
        }
        return sb.toString();
    }

    private static ExecutionResult result(String content, int stage) {
        return new ExecutionResult("claude", TaskType.CUSTOM, content).at(stage, "planner");
    }

    @Test
    void shortResultsPassUnchanged() {
        PromptCompiler compiler = new PromptCompiler(new PromptLimits(1_000, 3_000));
        CompiledPrompt base = compiler.compile(new ExecutionRequest("default", "x", List.of(), "ko"), TaskType.CUSTOM);
        List<String> notes = new ArrayList<>();
        String body = compiler.compileForStage(base, null, List.of(result("짧은 계획", 1)), PromptCompiler.StageContext.NONE, notes::add).body();
        assertTrue(body.contains("짧은 계획"));
        assertTrue(notes.isEmpty());
    }

    @Test
    void longResultKeepsHeadAndTailAndReportsTheCut() {
        PromptCompiler compiler = new PromptCompiler(new PromptLimits(2_000, 0));
        CompiledPrompt base = compiler.compile(new ExecutionRequest("default", "x", List.of(), "ko"), TaskType.CUSTOM);
        String plan = lines("plan", 200) + "결론: 승인";   // ~8,800 chars
        List<String> notes = new ArrayList<>();

        String body = compiler.compileForStage(base, null, List.of(result(plan, 1)), PromptCompiler.StageContext.NONE, notes::add).body();

        assertTrue(body.contains("plan 1:"), "head kept");
        assertTrue(body.contains("결론: 승인"), "tail kept");
        assertFalse(body.contains("plan 100:"), "middle dropped");
        assertTrue(body.contains("중간") && body.contains("자 생략"), body);
        assertTrue(body.length() < 2_000 + base.body().length() + 400, "close to the cap: " + body.length());
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("8,8".replace(",", "")) || notes.get(0).contains("자 →"), notes.get(0));
    }

    @Test
    void stageBudgetIsSharedAcrossPreviousResults() {
        PromptCompiler compiler = new PromptCompiler(new PromptLimits(0, 3_000));
        CompiledPrompt base = compiler.compile(new ExecutionRequest("default", "x", List.of(), "ko"), TaskType.CUSTOM);
        String review = lines("review", 60);   // ~2,700 chars each
        List<String> notes = new ArrayList<>();

        String body = compiler.compileForStage(base, null, List.of(result(review, 3), result(review, 3), result(review, 3)), PromptCompiler.StageContext.NONE, notes::add).body();

        int inherited = body.length() - base.body().length();
        assertTrue(inherited < 3_000 + 600, "three reviews fit the 3,000-char budget plus markers: " + inherited);
        assertTrue(notes.size() >= 2, "later results were cut: " + notes);
    }

    @Test
    void unlimitedKeepsEverything() {
        PromptCompiler compiler = new PromptCompiler(PromptLimits.UNLIMITED);
        CompiledPrompt base = compiler.compile(new ExecutionRequest("default", "x", List.of(), "ko"), TaskType.CUSTOM);
        String huge = lines("x", 5_000);
        List<String> notes = new ArrayList<>();
        String body = compiler.compileForStage(base, null, List.of(result(huge, 1)), PromptCompiler.StageContext.NONE, notes::add).body();
        assertTrue(body.contains("x 5000:"));
        assertTrue(notes.isEmpty());
    }

    @Test
    void defaultsMatchTheDocumentedBudget() {
        assertEquals(24_000, PromptLimits.DEFAULT.maxResultChars());
        assertEquals(60_000, PromptLimits.DEFAULT.maxTotalChars());
        assertEquals(24_000, new PromptCompiler().limits().maxResultChars());
    }
}
