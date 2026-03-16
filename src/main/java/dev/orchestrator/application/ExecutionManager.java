package dev.orchestrator.application;

import dev.orchestrator.domain.AiModule;
import dev.orchestrator.domain.CompiledPrompt;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ExecutionManager {
    private final TaskRouter taskRouter;
    private final PromptCompiler promptCompiler;
    private final Map<String, AiModule> modules;

    public ExecutionManager(TaskRouter taskRouter, PromptCompiler promptCompiler, AiModule... modules) {
        this.taskRouter = taskRouter;
        this.promptCompiler = promptCompiler;
        this.modules = Arrays.stream(modules)
                .collect(Collectors.toMap(AiModule::name, module -> module, (left, right) -> left, LinkedHashMap::new));
    }

    public String execute(ExecutionRequest request) {
        CompiledPrompt prompt = promptCompiler.compile(request);
        List<ExecutionResult> results = taskRouter.route(request).stream()
                .map(this::resolveModule)
                .map(module -> module.execute(prompt, request))
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("Task: ").append(request.taskType().name().toLowerCase()).append(System.lineSeparator());
        builder.append("Target: ").append(request.target()).append(System.lineSeparator());
        builder.append("Modules: ")
                .append(results.stream().map(ExecutionResult::moduleName).collect(Collectors.joining(", ")))
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (ExecutionResult result : results) {
            builder.append("[").append(result.moduleName()).append("]").append(System.lineSeparator());
            builder.append(result.content()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private AiModule resolveModule(String moduleName) {
        AiModule module = modules.get(moduleName);
        if (module == null) {
            throw new IllegalArgumentException("Unknown AI module: " + moduleName);
        }
        return module;
    }
}
