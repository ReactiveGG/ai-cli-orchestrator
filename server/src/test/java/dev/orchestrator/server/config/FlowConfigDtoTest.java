package dev.orchestrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AgentSpec;
import dev.orchestrator.domain.FlowDefinition;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowConfigDtoTest {
    @Test
    void roundTripsThroughYaml() {
        FlowConfigDto dto = new FlowConfigDto(
                Map.of("ship", new FlowConfigDto.FlowDto("배포 준비", "custom", "claude", List.of(
                        new FlowConfigDto.StageDto(null, "planner", List.of()),
                        new FlowConfigDto.StageDto("리뷰 2인", "reviewer", List.of(new FlowConfigDto.AgentDto("claude", "opus", "high"), new FlowConfigDto.AgentDto("codex", null, null))),
                        new FlowConfigDto.StageDto(null, "executor", List.of(new FlowConfigDto.AgentDto("codex", null, null)))))),
                Map.of("reviewer", new FlowConfigDto.RoleDto("리뷰어", "custom", true)),
                Map.of("claude", "codex"));

        FlowConfig direct = dto.toConfig();
        FlowConfig parsed = FlowConfig.fromYaml(new ByteArrayInputStream(dto.toYaml().getBytes(StandardCharsets.UTF_8)));

        for (FlowConfig config : List.of(direct, parsed)) {
            FlowDefinition ship = config.flow("ship");
            assertEquals("배포 준비", ship.label());
            assertEquals("플래너", ship.stages().get(0).name());
            assertEquals(List.of("planner@claude"), ship.stages().get(0).agents().stream().map(AgentSpec::label).toList());
            assertEquals("리뷰 2인", ship.stages().get(1).name());
            assertEquals(List.of("reviewer@claude", "reviewer@codex"), ship.stages().get(1).agents().stream().map(AgentSpec::label).toList());
            assertEquals("opus", ship.stages().get(1).agents().get(0).model());
            assertEquals("high", ship.stages().get(1).agents().get(0).effort());
            assertEquals("claude opus/high ∥ codex", ship.stages().get(1).describeAgents());
            assertEquals("executor", ship.stages().get(2).role());
            assertEquals(List.of("codex"), ship.stages().get(2).models());
            assertEquals("custom", config.role("reviewer").instructions());
            assertEquals("codex", config.fallbackFor("claude"));
        }
        FlowConfigDto back = FlowConfigDto.from(parsed);
        assertEquals("reviewer", back.flows().get("ship").stages().get(1).role());
        assertEquals("opus", back.flows().get("ship").stages().get(1).models().get(0).model());
        assertEquals("codex", back.flows().get("ship").stages().get(1).models().get(1).module());
        assertTrue(back.roles().get("executor").builtIn());
        assertEquals("1-2-1", back.flows().get("ship").stages().stream().map(st -> String.valueOf(st.models().size())).reduce((a, b) -> a + "-" + b).orElse(""));
    }

    @Test
    void rejectsUnknownRole() {
        FlowConfigDto dto = new FlowConfigDto(
                Map.of("x", new FlowConfigDto.FlowDto(null, null, "claude", List.of(new FlowConfigDto.StageDto(null, "ghost", List.of(new FlowConfigDto.AgentDto("claude", null, null)))))),
                Map.of(), Map.of());

        assertThrows(IllegalArgumentException.class, dto::toConfig);
    }
}
