package dev.orchestrator.server.job;

public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMEOUT;

    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }
}
