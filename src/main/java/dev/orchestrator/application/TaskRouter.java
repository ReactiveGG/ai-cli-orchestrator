package dev.orchestrator.application;

import dev.orchestrator.config.RoutingConfig;
import dev.orchestrator.domain.ExecutionRequest;
import java.util.List;

public final class TaskRouter {
    private final RoutingConfig routingConfig;

    public TaskRouter(RoutingConfig routingConfig) {
        this.routingConfig = routingConfig;
    }

    public List<String> route(ExecutionRequest request) {
        return routingConfig.modulesFor(request.taskType());
    }
}
