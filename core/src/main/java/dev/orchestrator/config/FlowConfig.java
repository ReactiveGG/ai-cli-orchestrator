package dev.orchestrator.config;

import dev.orchestrator.domain.AgentRole;
import dev.orchestrator.domain.AgentSpec;
import dev.orchestrator.domain.FlowDefinition;
import dev.orchestrator.domain.StageDefinition;
import dev.orchestrator.domain.TaskType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * All flows a user can run, the roles they reference, and timeout fallbacks.
 *
 * <p>YAML shape:
 * <pre>
 * flows:
 *   implement:                       # run with: implement &lt;target&gt;  (or run implement &lt;target&gt;)
 *     label: 구현
 *     defaultModule: claude          # model for stages that list none
 *     stages:
 *       - role: planner              # stage = role; models default to defaultModule
 *       - role: coder
 *       - role: reviewer
 *         models: [claude, codex]    # parallel, both outputs go on
 *   verify:
 *     stages:
 *       - role: executor             # no instructions: prompt runs as is
 *         models: [codex, claude]
 *   # legacy form still accepted: - { name: 리뷰, agents: [reviewer@claude, reviewer@codex] }
 * roles:
 *   reviewer: { label: 리뷰어, instructions: "역할: 리뷰어. ..." }
 * fallback:
 *   claude: codex
 * </pre>
 */
public final class FlowConfig {
    private final Map<String, FlowDefinition> flows;
    private final Map<String, AgentRole> roles;
    private final Map<String, String> fallback;

    public FlowConfig(Map<String, FlowDefinition> flows, Map<String, AgentRole> roles, Map<String, String> fallback) {
        this.flows = new LinkedHashMap<>(flows);
        this.roles = new LinkedHashMap<>(AgentRole.BUILT_IN);
        this.roles.putAll(roles);
        this.fallback = new LinkedHashMap<>(fallback);
    }

    public Map<String, FlowDefinition> flows() {
        return Collections.unmodifiableMap(flows);
    }

    public FlowDefinition flow(String name) {
        FlowDefinition flow = flows.get(name);
        if (flow == null) {
            throw new IllegalArgumentException("Unknown flow: " + name);
        }
        return flow;
    }

    public boolean hasFlow(String name) {
        return flows.containsKey(name);
    }

    /** All known roles: built-ins plus YAML additions/overrides. */
    public Map<String, AgentRole> roles() {
        return Collections.unmodifiableMap(roles);
    }

    public AgentRole role(String name) {
        AgentRole role = roles.get(name);
        if (role == null) {
            throw new IllegalArgumentException("Unknown agent role: " + name);
        }
        return role;
    }

    /** Module to try when {@code moduleName} times out, or null. */
    public String fallbackFor(String moduleName) {
        return fallback.get(moduleName);
    }

    public Map<String, String> fallbacks() {
        return Collections.unmodifiableMap(fallback);
    }

    /** Validates that every role a flow references exists. */
    public void validate() {
        for (FlowDefinition flow : flows.values()) {
            for (StageDefinition stage : flow.stages()) {
                for (AgentSpec agent : stage.agents()) {
                    if (agent.role() != null && !roles.containsKey(agent.role())) {
                        throw new IllegalArgumentException("Flow '" + flow.name() + "' uses unknown role '" + agent.role() + "'");
                    }
                }
            }
        }
    }

    // ---- presets ---------------------------------------------------------

    public static final String DEFAULT_PRESET = "default";

    /** Built-in presets over the fixed pipeline, all on {@code claude}. */
    public static FlowConfig defaultConfig() {
        return singleModule("claude");
    }

    /**
     * The built-in presets on one model:
     * default 1-1-1-1, cross-review 1-1-2-1 (two reviewers cross-check),
     * best-of-3 1-3-3-1 (three implementations, three reviews, verifier picks one).
     */
    public static FlowConfig singleModule(String module) {
        Map<String, FlowDefinition> flows = new LinkedHashMap<>();
        List<String> one = List.of(module);
        List<String> two = List.of(module, module);
        List<String> three = List.of(module, module, module);
        flows.put(DEFAULT_PRESET, FlowDefinition.preset(DEFAULT_PRESET, "기본", module, List.of(one, one, one, one)));
        flows.put("cross-review", FlowDefinition.preset("cross-review", "교차 리뷰", module, List.of(one, one, two, one)));
        flows.put("best-of-3", FlowDefinition.preset("best-of-3", "3안 비교", module, List.of(one, three, three, one)));
        return new FlowConfig(flows, Map.of(), Map.of());
    }

    /** The preset to run when a command names none. */
    public FlowDefinition defaultFlow() {
        if (flows.containsKey(DEFAULT_PRESET)) {
            return flows.get(DEFAULT_PRESET);
        }
        return flows.values().iterator().next();
    }

    // ---- YAML ------------------------------------------------------------

    /** Loads {@code path} if it exists, otherwise returns {@link #defaultConfig()}. */
    public static FlowConfig loadOrDefault(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return defaultConfig();
        }
        try (InputStream in = Files.newInputStream(path)) {
            return fromYaml(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read flow config " + path, e);
        }
    }

    public static FlowConfig fromYaml(InputStream in) {
        Object loaded = new Yaml().load(in);
        if (!(loaded instanceof Map<?, ?> root)) {
            return defaultConfig();
        }
        Map<String, AgentRole> roles = new LinkedHashMap<>();
        if (root.get("roles") instanceof Map<?, ?> rolesMap) {
            for (Map.Entry<?, ?> entry : rolesMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                AgentRole base = AgentRole.BUILT_IN.getOrDefault(name, new AgentRole(name, name, ""));
                if (entry.getValue() instanceof Map<?, ?> roleMap) {
                    String label = roleMap.get("label") == null ? base.labelKo() : String.valueOf(roleMap.get("label"));
                    String instructions = roleMap.get("instructions") == null ? base.instructions() : String.valueOf(roleMap.get("instructions"));
                    roles.put(name, new AgentRole(name, label, instructions));
                } else if (entry.getValue() != null) {
                    roles.put(name, new AgentRole(name, base.labelKo(), String.valueOf(entry.getValue())));
                }
            }
        }
        Map<String, AgentRole> allRoles = new LinkedHashMap<>(AgentRole.BUILT_IN);
        allRoles.putAll(roles);

        Map<String, FlowDefinition> flows = new LinkedHashMap<>();
        if (root.get("flows") instanceof Map<?, ?> flowsMap) {
            for (Map.Entry<?, ?> entry : flowsMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                flows.put(name, parseFlow(name, entry.getValue(), allRoles));
            }
        }
        if (flows.isEmpty()) {
            flows.putAll(defaultConfig().flows);
        }

        Map<String, String> fallback = new LinkedHashMap<>();
        if (root.get("fallback") instanceof Map<?, ?> fallbackMap) {
            fallbackMap.forEach((key, value) -> fallback.put(String.valueOf(key), String.valueOf(value)));
        }
        FlowConfig config = new FlowConfig(flows, roles, fallback);
        config.validate();
        return config;
    }

    private static FlowDefinition parseFlow(String name, Object node, Map<String, AgentRole> roles) {
        String label = null;
        String defaultModule = null;
        TaskType taskType = null;
        Object stagesNode = node;
        if (node instanceof Map<?, ?> map) {
            label = map.get("label") == null ? null : String.valueOf(map.get("label"));
            defaultModule = map.get("defaultModule") == null ? null : String.valueOf(map.get("defaultModule"));
            taskType = map.get("task") == null ? null : TaskType.fromFlowName(String.valueOf(map.get("task")));
            stagesNode = map.get("stages");
        }
        List<StageDefinition> stages = new ArrayList<>();
        if (stagesNode instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                index++;
                stages.add(parseStage(item, "단계 " + index, defaultModule, roles));
            }
        }
        return new FlowDefinition(name, label, taskType, defaultModule, stages);
    }

    private static StageDefinition parseStage(Object node, String fallbackName, String defaultModule, Map<String, AgentRole> roles) {
        java.util.function.Predicate<String> isRole = roles::containsKey;
        String stageName = null;
        Object agentsNode = node;
        if (node instanceof Map<?, ?> map && map.containsKey("role")) {
            // stage = role form: { role: reviewer, models: [claude, codex], name?: ... }
            String role = String.valueOf(map.get("role"));
            if (!isRole.test(role)) {
                throw new IllegalArgumentException("Unknown agent role: " + role);
            }
            List<String> models = new ArrayList<>();
            if (map.get("models") instanceof List<?> list) {
                list.forEach(item -> models.add(String.valueOf(item)));
            } else if (map.get("models") != null) {
                models.add(String.valueOf(map.get("models")));
            } else if (map.get("model") != null) {
                models.add(String.valueOf(map.get("model")));
            } else if (defaultModule != null) {
                models.add(defaultModule);
            }
            if (models.isEmpty()) {
                throw new IllegalArgumentException("Stage '" + role + "' needs models (or set defaultModule)");
            }
            String name = map.get("name") == null ? roles.get(role).labelKo() : String.valueOf(map.get("name"));
            return StageDefinition.ofRole(name, role, models);
        }
        if (node instanceof Map<?, ?> map && map.containsKey("agents")) {
            stageName = map.get("name") == null ? null : String.valueOf(map.get("name"));
            agentsNode = map.get("agents");
        }
        List<AgentSpec> agents = new ArrayList<>();
        if (agentsNode instanceof List<?> list) {
            for (Object item : list) {
                agents.add(parseAgent(item, defaultModule, isRole));
            }
        } else if (agentsNode != null) {
            agents.add(parseAgent(agentsNode, defaultModule, isRole));
        }
        return new StageDefinition(stageName == null ? fallbackName : stageName, agents);
    }

    private static AgentSpec parseAgent(Object node, String defaultModule, java.util.function.Predicate<String> isRole) {
        if (node instanceof Map<?, ?> map) {
            String role = map.get("role") == null ? null : String.valueOf(map.get("role"));
            String module = map.get("module") == null ? defaultModule : String.valueOf(map.get("module"));
            return new AgentSpec(role, module);
        }
        return AgentSpec.parse(String.valueOf(node), defaultModule, isRole);
    }
}
