package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AgentSpec;
import dev.orchestrator.domain.FlowDefinition;
import dev.orchestrator.domain.TaskType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowConfigTest {
    private static FlowConfig parse(String yaml) {
        return FlowConfig.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesPresetsInRoleModelsForm() {
        FlowConfig config = parse("""
                flows:
                  cross-review:
                    label: 교차 리뷰
                    defaultModule: claude
                    stages:
                      - role: planner
                      - role: coder
                      - role: reviewer
                        models: [claude:opus/high, codex]
                      - { role: verifier, models: [{ module: claude, model: sonnet, effort: max }], name: 최종 검증 }
                  legacy:
                    stages:
                      - name: 실행
                        agents: [executor@codex, executor@claude]
                roles:
                  reviewer:
                    instructions: custom reviewer prompt
                fallback:
                  claude: codex
                """);

        FlowDefinition preset = config.flow("cross-review");
        assertEquals("교차 리뷰", preset.label());
        assertEquals(TaskType.CUSTOM, preset.taskType());
        assertEquals("1-1-2-1", preset.signature());
        assertEquals(List.of("플래너", "코더", "리뷰어", "최종 검증"), preset.stages().stream().map(s -> s.name()).toList());
        assertEquals(List.of("reviewer@claude", "reviewer@codex"), preset.stages().get(2).agents().stream().map(AgentSpec::label).toList());
        assertEquals("opus", preset.stages().get(2).agents().get(0).model());
        assertEquals("high", preset.stages().get(2).agents().get(0).effort());
        assertEquals("sonnet", preset.stages().get(3).agents().get(0).model());
        assertEquals("max", preset.stages().get(3).agents().get(0).effort());
        assertEquals("리뷰어(claude opus/high ∥ codex)", preset.stages().get(2).name() + "(" + preset.stages().get(2).describeAgents() + ")");
        assertEquals(List.of("claude", "codex"), preset.modules());

        FlowDefinition legacy = config.flow("legacy");
        assertEquals("executor", legacy.stages().get(0).role());
        assertEquals(List.of("codex", "claude"), legacy.stages().get(0).models());

        assertEquals("custom reviewer prompt", config.role("reviewer").instructions());
        assertEquals("codex", config.fallbackFor("claude"));
        assertTrue(!config.hasFlow("default"), "flows in YAML replace the defaults");
        assertEquals("cross-review", config.defaultFlow().name());
    }

    @Test
    void rejectsUnknownRolesAndBareRolesWithoutDefaultModule() {
        assertThrows(IllegalArgumentException.class, () -> parse("flows:\n  x:\n    stages: [[ghost@claude]]\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("flows:\n  x:\n    stages:\n      - role: planner\n"));
    }

    @Test
    void defaultPresetsRunTheFixedPipeline() {
        FlowConfig defaults = FlowConfig.loadOrDefault(Path.of("does-not-exist.yml"));
        assertEquals(List.of("default", "cross-review", "best-of-3"), List.copyOf(defaults.flows().keySet()));
        assertEquals("1-1-1-1", defaults.flow("default").signature());
        assertEquals("1-1-2-1", defaults.flow("cross-review").signature());
        assertEquals("1-3-3-1", defaults.flow("best-of-3").signature());
        assertEquals("플래너(claude) → 코더(claude ∥ claude ∥ claude) → 리뷰어(claude ∥ claude ∥ claude) → 검증자(claude)", defaults.flow("best-of-3").describe());
        assertEquals("default", defaults.defaultFlow().name());
        assertEquals(List.of("planner", "coder", "reviewer", "verifier"), defaults.flow("default").stages().stream().map(s -> s.role()).toList());
    }
}
