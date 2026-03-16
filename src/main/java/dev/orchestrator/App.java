package dev.orchestrator;

import picocli.CommandLine;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(OrchestratorCommand.createDefault()).execute(args);
        System.exit(exitCode);
    }
}
