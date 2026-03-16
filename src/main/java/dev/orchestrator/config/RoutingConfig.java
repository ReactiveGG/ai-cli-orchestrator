package dev.orchestrator.config;

import dev.orchestrator.domain.TaskType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RoutingConfig {
    private final Map<TaskType, List<String>> routes;

    public RoutingConfig(Map<TaskType, List<String>> routes) {
        this.routes = new EnumMap<>(routes);
    }

    public List<String> modulesFor(TaskType taskType) {
        return routes.getOrDefault(taskType, List.of());
    }

    public static RoutingConfig defaultConfig() {
        Map<TaskType, List<String>> routes = new EnumMap<>(TaskType.class);
        routes.put(TaskType.ANALYZE, List.of("codex"));
        routes.put(TaskType.IMPLEMENT, List.of("claude"));
        routes.put(TaskType.REVIEW, List.of("claude"));
        routes.put(TaskType.VERIFY, List.of("codex", "claude"));
        return new RoutingConfig(routes);
    }
}
