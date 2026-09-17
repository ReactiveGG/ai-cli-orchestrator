package dev.orchestrator.server.config;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.AgentRole;
import dev.orchestrator.domain.AgentSpec;
import dev.orchestrator.domain.FlowDefinition;
import dev.orchestrator.domain.StageDefinition;
import dev.orchestrator.domain.TaskType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * JSON shape of the flow config as edited by the drag-and-drop builder, plus
 * conversion to/from the core {@link FlowConfig} and to YAML for persistence.
 */
public record FlowConfigDto(
        Map<String, FlowDto> flows,
        Map<String, RoleDto> roles,
        Map<String, String> fallback
) {
    /** One agent of a stage: which tool runs it and, optionally, which model variant and effort. */
    public record AgentDto(String module, String model, String effort) {
    }

    /**
     * @param role   role every agent in this stage plays ({@code executor} = prompt as is)
     * @param models agents that run this stage in parallel; empty = one run on the flow's defaultModule
     */
    public record StageDto(String name, String role, List<AgentDto> models) {
    }

    public record FlowDto(String label, String task, String defaultModule, List<StageDto> stages) {
    }

    public record RoleDto(String label, String instructions, boolean builtIn) {
    }

    public static FlowConfigDto from(FlowConfig config) {
        Map<String, FlowDto> flows = new LinkedHashMap<>();
        config.flows().forEach((name, flow) -> flows.put(name, new FlowDto(
                flow.label(),
                flow.taskType().name().toLowerCase(Locale.ROOT),
                flow.defaultModule(),
                flow.stages().stream().map(stage -> new StageDto(stage.name(),
                        stage.role() == null ? "executor" : stage.role(),
                        stage.agents().stream().map(a -> new AgentDto(a.module(), a.model(), a.effort())).toList())).toList())));
        Map<String, RoleDto> roles = new LinkedHashMap<>();
        config.roles().forEach((name, role) -> roles.put(name,
                new RoleDto(role.labelKo(), role.instructions(), AgentRole.BUILT_IN.containsKey(name))));
        return new FlowConfigDto(flows, roles, config.fallbacks());
    }

    public FlowConfig toConfig() {
        Map<String, AgentRole> roleMap = new LinkedHashMap<>();
        if (roles != null) {
            roles.forEach((name, dto) -> {
                AgentRole base = AgentRole.BUILT_IN.getOrDefault(name, new AgentRole(name, name, ""));
                roleMap.put(name, new AgentRole(name,
                        dto.label() == null || dto.label().isBlank() ? base.labelKo() : dto.label(),
                        dto.instructions() == null ? base.instructions() : dto.instructions()));
            });
        }
        Map<String, FlowDefinition> flowMap = new LinkedHashMap<>();
        if (flows != null) {
            flows.forEach((name, dto) -> {
                List<StageDefinition> stages = new ArrayList<>();
                int index = 0;
                for (StageDto stage : dto.stages() == null ? List.<StageDto>of() : dto.stages()) {
                    index++;
                    String role = stage.role() == null || stage.role().isBlank() ? "executor" : stage.role();
                    List<AgentDto> models = stage.models() == null || stage.models().isEmpty()
                            ? (dto.defaultModule() == null ? List.<AgentDto>of() : List.of(new AgentDto(dto.defaultModule(), null, null)))
                            : stage.models();
                    List<AgentSpec> agents = new ArrayList<>();
                    for (AgentDto a : models) {
                        String module = a.module() == null || a.module().isBlank() ? dto.defaultModule() : a.module();
                        agents.add(new AgentSpec(role, module, a.model(), a.effort()));
                    }
                    AgentRole known = roleMap.containsKey(role) ? roleMap.get(role) : AgentRole.BUILT_IN.get(role);
                    String stageName = stage.name() == null || stage.name().isBlank() ? (known == null ? role : known.labelKo()) : stage.name();
                    stages.add(new StageDefinition(stageName, agents));
                }
                flowMap.put(name, new FlowDefinition(name, dto.label(),
                        dto.task() == null ? null : TaskType.fromFlowName(dto.task()), dto.defaultModule(), stages));
            });
        }
        FlowConfig config = new FlowConfig(flowMap, roleMap, fallback == null ? Map.of() : fallback);
        config.validate();
        return config;
    }

    /** YAML in the shape {@link FlowConfig#fromYaml} reads. */
    public String toYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> flowNode = new LinkedHashMap<>();
        if (flows != null) {
            flows.forEach((name, dto) -> {
                Map<String, Object> node = new LinkedHashMap<>();
                if (dto.label() != null && !dto.label().isBlank()) {
                    node.put("label", dto.label());
                }
                if (dto.task() != null && !"custom".equals(dto.task()) && !dto.task().equals(name)) {
                    node.put("task", dto.task());
                }
                if (dto.defaultModule() != null && !dto.defaultModule().isBlank()) {
                    node.put("defaultModule", dto.defaultModule());
                }
                List<Object> stages = new ArrayList<>();
                for (StageDto stage : dto.stages() == null ? List.<StageDto>of() : dto.stages()) {
                    Map<String, Object> stageNode = new LinkedHashMap<>();
                    stageNode.put("role", stage.role() == null || stage.role().isBlank() ? "executor" : stage.role());
                    if (stage.models() != null && !stage.models().isEmpty()) {
                        stageNode.put("models", stage.models().stream().map(a -> {
                            String module = a.module() == null || a.module().isBlank() ? dto.defaultModule() : a.module();
                            boolean plain = (a.model() == null || a.model().isBlank()) && (a.effort() == null || a.effort().isBlank());
                            if (plain) {
                                return (Object) module;
                            }
                            Map<String, Object> agentNode = new LinkedHashMap<>();
                            agentNode.put("module", module);
                            if (a.model() != null && !a.model().isBlank()) {
                                agentNode.put("model", a.model());
                            }
                            if (a.effort() != null && !a.effort().isBlank()) {
                                agentNode.put("effort", a.effort());
                            }
                            return (Object) agentNode;
                        }).toList());
                    }
                    AgentRole known = AgentRole.BUILT_IN.get(stageNode.get("role"));
                    if (stage.name() != null && !stage.name().isBlank() && (known == null || !known.labelKo().equals(stage.name()))) {
                        stageNode.put("name", stage.name());
                    }
                    stages.add(stageNode);
                }
                node.put("stages", stages);
                flowNode.put(name, node);
            });
        }
        root.put("flows", flowNode);
        Map<String, Object> roleNode = new LinkedHashMap<>();
        if (roles != null) {
            roles.forEach((name, dto) -> {
                AgentRole base = AgentRole.BUILT_IN.get(name);
                boolean unchanged = base != null && base.labelKo().equals(dto.label()) && base.instructions().equals(dto.instructions());
                if (unchanged) {
                    return;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                if (dto.label() != null) {
                    node.put("label", dto.label());
                }
                node.put("instructions", dto.instructions() == null ? "" : dto.instructions());
                roleNode.put(name, node);
            });
        }
        root.put("roles", roleNode);
        root.put("fallback", fallback == null ? Map.of() : fallback);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options).dump(root);
    }
}
