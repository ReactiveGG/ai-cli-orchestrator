package dev.orchestrator.server.job;

import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.TokenUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mutable job state. All mutation goes through synchronized methods; readers take snapshots. */
public final class Job {
    /** Detail events beyond this count are kept on disk only. */
    static final int MAX_EVENTS_IN_MEMORY = 20_000;

    private final String id;
    private final ExecutionRequest request;
    private final String command;
    private volatile String flowLabel;
    private final Instant createdAt;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final List<JobEvent> events = new ArrayList<>();
    private final Map<String, JobStep> steps = new LinkedHashMap<>();
    private final Map<String, TokenUsage> usageByModule = new LinkedHashMap<>();

    private JobStatus status = JobStatus.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant lastOutputAt;
    private TokenUsage usage = TokenUsage.ZERO;
    private String error;
    private String result;
    private long seq = 0;
    private long droppedEvents = 0;

    public Job(String id, ExecutionRequest request) {
        this(id, request, Instant.now());
    }

    public Job(String id, ExecutionRequest request, Instant createdAt) {
        this.id = id;
        this.request = request;
        this.command = JobRequest.display(request);
        this.createdAt = createdAt;
    }

    /** Rebuilds a job from a persisted snapshot (used at startup). */
    public static Job fromSnapshot(JobSnapshot snapshot) {
        ExecutionRequest request = new ExecutionRequest(
                snapshot.flow(), snapshot.target(), snapshot.focus(), snapshot.language());
        Job job = new Job(snapshot.id(), request, snapshot.createdAt());
        job.flowLabel = snapshot.flowLabel();
        job.status = snapshot.status();
        job.startedAt = snapshot.startedAt();
        job.finishedAt = snapshot.finishedAt();
        job.lastOutputAt = snapshot.lastOutputAt();
        job.usage = snapshot.usage() == null ? TokenUsage.ZERO : snapshot.usage();
        if (snapshot.usageByModule() != null) {
            job.usageByModule.putAll(snapshot.usageByModule());
        }
        job.error = snapshot.error();
        job.result = snapshot.result();
        job.seq = snapshot.eventCount();
        job.droppedEvents = snapshot.eventCount();
        if (snapshot.steps() != null) {
            snapshot.steps().forEach(step -> job.steps.put(step.id(), step));
        }
        return job;
    }

    public String id() {
        return id;
    }

    public ExecutionRequest request() {
        return request;
    }

    public void setFlowLabel(String label) {
        this.flowLabel = label;
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public void requestCancel() {
        cancelRequested.set(true);
    }

    public synchronized JobStatus status() {
        return status;
    }

    public synchronized void setPlan(List<JobStep> plan) {
        steps.clear();
        plan.forEach(step -> steps.put(step.id(), step));
    }

    public synchronized void updateStep(String stepId, StepStatus stepStatus, String stepError) {
        JobStep step = steps.get(stepId);
        if (step == null) {
            return;
        }
        Instant now = Instant.now();
        Instant started = stepStatus == StepStatus.RUNNING ? now : step.startedAt();
        Instant finished = stepStatus == StepStatus.DONE || stepStatus == StepStatus.FAILED ? now : step.finishedAt();
        steps.put(stepId, step.with(stepStatus, started, finished, stepError));
    }

    /** Points a step at another module after a fallback so the graph reflects what ran. */
    public synchronized void relabelStepModule(String stepId, String module) {
        JobStep step = steps.get(stepId);
        if (step != null) {
            steps.put(stepId, step.withModule(module));
        }
    }

    public synchronized void start() {
        status = JobStatus.RUNNING;
        startedAt = Instant.now();
        lastOutputAt = startedAt;
    }

    public synchronized void finish(JobStatus terminal, String errorMessage, String finalResult) {
        status = terminal;
        finishedAt = Instant.now();
        error = errorMessage;
        result = finalResult;
        steps.replaceAll((stepId, step) -> step.status() == StepStatus.PENDING || step.status() == StepStatus.RUNNING
                ? step.with(step.status() == StepStatus.RUNNING ? StepStatus.FAILED : StepStatus.SKIPPED,
                        step.startedAt(), step.status() == StepStatus.RUNNING ? finishedAt : step.finishedAt(), step.error())
                : step);
    }

    public synchronized void addUsage(String module, TokenUsage moduleUsage) {
        usage = usage.plus(moduleUsage);
        usageByModule.merge(module, moduleUsage, TokenUsage::plus);
    }

    public synchronized JobEvent addEvent(JobEventLevel level, String stepId, String message) {
        JobEvent event = new JobEvent(++seq, Instant.now(), id, level, stepId, message);
        lastOutputAt = event.at();
        events.add(event);
        if (events.size() > MAX_EVENTS_IN_MEMORY) {
            events.remove(0);
            droppedEvents++;
        }
        return event;
    }

    /** Events with seq > after, oldest first. */
    public synchronized List<JobEvent> eventsAfter(long after, JobEventLevel levelFilter) {
        List<JobEvent> out = new ArrayList<>();
        for (JobEvent event : events) {
            if (event.seq() > after && (levelFilter == null || event.level() == levelFilter)) {
                out.add(event);
            }
        }
        return out;
    }

    public synchronized boolean hasDroppedEvents() {
        return droppedEvents > 0;
    }

    public synchronized JobSnapshot snapshot() {
        return new JobSnapshot(
                id, status, command, request.flow(), flowLabel == null ? request.flow() : flowLabel, request.target(), request.focus(),
                request.responseLanguage(), createdAt, startedAt, finishedAt, lastOutputAt,
                List.copyOf(steps.values()), usage, Map.copyOf(usageByModule), error, result, seq
        );
    }
}
