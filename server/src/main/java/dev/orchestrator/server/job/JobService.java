package dev.orchestrator.server.job;

import dev.orchestrator.application.ExecutionManager;
import dev.orchestrator.application.ExecutionObserver;
import dev.orchestrator.application.ExecutionStep;
import dev.orchestrator.application.IsolationSettings;
import dev.orchestrator.isolation.CandidatePatch;
import dev.orchestrator.isolation.IsolationException;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.orchestrator.domain.ExecutionReport;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.domain.ExecutionResult;
import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.FlowDefinition;
import dev.orchestrator.domain.ModuleExecutionException;
import dev.orchestrator.server.config.OrchestrationService;
import dev.orchestrator.server.config.OrchestratorProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Job queue and runner. One command = one job = one one-way pass through
 * {@link ExecutionManager}. At most {@code orchestrator.concurrency} jobs run
 * at once; the rest wait as QUEUED.
 */
@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final OrchestrationService orchestration;
    private final JobStore store;
    private final JobEventBus bus = new JobEventBus();
    private final java.util.concurrent.ThreadPoolExecutor executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final OrchestratorProperties.Retention retention;
    private volatile java.util.function.Consumer<dev.orchestrator.domain.RateLimitInfo> rateLimitListener = info -> { };
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private volatile int concurrency;

    @Autowired
    public JobService(OrchestrationService orchestration, OrchestratorProperties properties) throws IOException {
        this(orchestration, new JobStore(properties.jobsDir()), properties.concurrency(), properties.retention());
    }

    JobService(OrchestrationService orchestration, JobStore store, int concurrency) {
        this(orchestration, store, concurrency, new OrchestratorProperties.Retention(0, Duration.ZERO));
    }

    JobService(OrchestrationService orchestration, JobStore store, int concurrency, OrchestratorProperties.Retention retention) {
        this.orchestration = orchestration;
        this.store = store;
        this.retention = retention;
        this.concurrency = Math.max(1, concurrency);
        this.executor = new java.util.concurrent.ThreadPoolExecutor(this.concurrency, this.concurrency, 60, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "job-runner");
                    thread.setDaemon(true);
                    return thread;
                });
        for (JobSnapshot snapshot : store.loadAll()) {
            Job job = Job.fromSnapshot(snapshot, store.readEvents(snapshot.id()));
            if (!snapshot.status().isTerminal()) {
                job.finish(JobStatus.FAILED, "서버가 재시작되어 중단됨", null);
                store.save(job.snapshot());
            }
            jobs.put(job.id(), job);
        }
        log.info("Loaded {} persisted jobs, concurrency {}", jobs.size(), this.concurrency);
        prune();
    }

    /**
     * Deletes finished jobs beyond the retention limits (count and age), newest kept. Runs at
     * startup and whenever a job finishes, so the data directory stays bounded without a cron.
     */
    synchronized int prune() {
        int maxJobs = retention.maxJobs();
        Duration maxAge = retention.maxAge();
        boolean byAge = maxAge != null && !maxAge.isZero() && !maxAge.isNegative();
        if (maxJobs <= 0 && !byAge) {
            return 0;
        }
        List<Job> finished = jobs.values().stream()
                .filter(j -> j.status().isTerminal())
                .sorted(Comparator.comparing((Job j) -> j.snapshot().createdAt()).reversed())
                .toList();
        Instant cutoff = byAge ? Instant.now().minus(maxAge) : null;
        int removed = 0;
        for (int i = 0; i < finished.size(); i++) {
            Job job = finished.get(i);
            JobSnapshot s = job.snapshot();
            Instant when = s.finishedAt() != null ? s.finishedAt() : s.createdAt();
            boolean tooMany = maxJobs > 0 && i >= maxJobs;
            boolean tooOld = cutoff != null && when.isBefore(cutoff);
            if (!tooMany && !tooOld) {
                continue;
            }
            try {
                jobs.remove(job.id());
                futures.remove(job.id());
                store.delete(job.id());
                removed++;
            } catch (IOException e) {
                log.warn("Could not delete old job {}: {}", job.id(), e.toString());
            }
        }
        if (removed > 0) {
            log.info("Pruned {} finished job(s) (keep {} / {})", removed, maxJobs > 0 ? maxJobs : "all", byAge ? maxAge : "any age");
            bus.publishJobs(list());
        }
        return removed;
    }

    /** Receives every subscription-usage report the CLIs emit (see SubscriptionUsage). */
    public void setRateLimitListener(java.util.function.Consumer<dev.orchestrator.domain.RateLimitInfo> listener) {
        this.rateLimitListener = listener == null ? info -> { } : listener;
    }

    public int concurrency() {
        return concurrency;
    }

    /** Changes how many jobs run at once; queued jobs pick up the new limit immediately. */
    public synchronized void setConcurrency(int limit) {
        int n = Math.max(1, Math.min(16, limit));
        if (n >= executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(n);
            executor.setCorePoolSize(n);
        } else {
            executor.setCorePoolSize(n);
            executor.setMaximumPoolSize(n);
        }
        concurrency = n;
    }

    public int runningCount() {
        return running.get();
    }

    public JobSnapshot submit(ExecutionRequest request) {
        FlowConfig config = orchestration.config();
        if (!config.hasFlow(request.flow()) && FlowConfig.DEFAULT_PRESET.equals(request.flow())) {
            request = new ExecutionRequest(config.defaultFlow().name(), request.target(), request.focus(), request.responseLanguage());
        }
        FlowDefinition flow = config.flow(request.flow());
        String id = Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Job job = new Job(id, request);
        job.setFlowLabel(flow.label());
        jobs.put(id, job);
        emit(job, JobEventLevel.SUMMARY, null, "대기열에 추가됨: " + JobRequest.display(request));
        JobSnapshot queued = job.snapshot();   // taken before the runner can start, so the response always says QUEUED
        store.save(queued);
        bus.publishJob(queued);
        futures.put(id, executor.submit(() -> run(job)));
        return queued;
    }

    public List<JobSnapshot> submitAll(List<ExecutionRequest> requests) {
        return requests.stream().map(this::submit).toList();
    }

    public List<JobSnapshot> list() {
        return jobs.values().stream()
                .map(Job::snapshot)
                .sorted(Comparator.comparing(JobSnapshot::createdAt).reversed())
                .toList();
    }

    /**
     * Newest first, filtered and paged for the history view.
     *
     * @param query  case-insensitive substring of the command, target, preset or id (null/blank = all)
     * @param status exact status (null = all)
     * @param offset rows to skip
     * @param limit  max rows (<= 0 = all)
     */
    public List<JobSnapshot> list(String query, JobStatus status, int offset, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        var stream = list().stream()
                .filter(s -> status == null || s.status() == status)
                .filter(s -> q.isEmpty() || s.command().toLowerCase().contains(q) || s.id().contains(q)
                        || s.flow().toLowerCase().contains(q) || (s.flowLabel() != null && s.flowLabel().toLowerCase().contains(q)))
                .skip(Math.max(0, offset));
        return (limit > 0 ? stream.limit(limit) : stream).toList();
    }

    public int count(String query, JobStatus status) {
        return list(query, status, 0, 0).size();
    }

    public JobSnapshot get(String id) {
        return job(id).snapshot();
    }

    public List<ExecutionStep> preview(ExecutionRequest request) {
        return orchestration.manager().plan(request);
    }

    public JobSnapshot cancel(String id) {
        Job job = job(id);
        job.requestCancel();
        Future<?> future = futures.get(id);
        if (job.status() == JobStatus.QUEUED && future != null && future.cancel(false)) {
            emit(job, JobEventLevel.SUMMARY, null, "대기 중 취소됨");
            job.finish(JobStatus.CANCELLED, "사용자 취소", null);
            store.save(job.snapshot());
            store.close(id);
            bus.publishJob(job.snapshot());
            bus.completeJob(id);
        } else if (job.status() == JobStatus.RUNNING) {
            emit(job, JobEventLevel.SUMMARY, null, "취소 요청됨, 실행 중인 프로세스를 종료합니다");
        }
        return job.snapshot();
    }

    public void delete(String id) throws IOException {
        Job job = job(id);
        if (!job.status().isTerminal()) {
            throw new IllegalStateException("Cannot delete a job that is still " + job.status());
        }
        jobs.remove(id);
        futures.remove(id);
        store.delete(id);
    }

    /** Applies candidate {@code index}'s patch to the workspace by hand (also to override the verifier's choice). */
    public JobSnapshot applyCandidate(String id, int index) {
        Job job = job(id);
        if (!job.status().isTerminal()) {
            throw new IllegalStateException("작업이 끝난 뒤에만 후보를 적용할 수 있습니다");
        }
        JobCandidate candidate = job.candidate(index).orElseThrow(() -> new IllegalArgumentException("후보 " + index + "이(가) 없습니다"));
        if (candidate.empty()) {
            throw new IllegalStateException("후보 " + index + "은(는) 변경이 없습니다");
        }
        IsolationSettings settings = orchestration.manager().isolationSettings();
        if (!settings.enabled()) {
            throw new IllegalStateException("작업 공간 격리가 꺼져 있어 적용할 수 없습니다");
        }
        Path patchFile = Path.of(candidate.patchFile());
        String patchText;
        try {
            patchText = Files.readString(patchFile);
        } catch (IOException e) {
            throw new IllegalStateException("patch 파일을 읽을 수 없습니다: " + patchFile);
        }
        CandidatePatch patch = new CandidatePatch(index, "", "", candidate.stat(), patchText, patchFile,
                candidate.filesChanged(), candidate.insertions(), candidate.deletions());
        try {
            settings.isolation().apply(patch, settings.workspace());
        } catch (IsolationException e) {
            throw new IllegalStateException(e.getMessage());
        }
        String note = "후보 " + index + " 수동 적용됨 (" + candidate.summary() + ")";
        job.markApplied(index, note);
        emit(job, JobEventLevel.SUMMARY, null, note);
        store.save(job.snapshot());
        bus.publishJob(job.snapshot());
        return job.snapshot();
    }

    /** The saved diff of one candidate ("" when it changed nothing). */
    public String candidatePatch(String id, int index) {
        JobCandidate candidate = job(id).candidate(index).orElseThrow(() -> new IllegalArgumentException("후보 " + index + "이(가) 없습니다"));
        if (candidate.patchFile() == null) {
            return "";
        }
        try {
            return Files.readString(Path.of(candidate.patchFile()));
        } catch (IOException e) {
            throw new IllegalStateException("patch 파일을 읽을 수 없습니다: " + candidate.patchFile());
        }
    }

    public List<JobEvent> events(String id, long after, JobEventLevel level) {
        return job(id).eventsAfter(after, level);
    }

    /** Full log lines from disk (used when in-memory events were trimmed). */
    public List<String> logLines(String id, JobEventLevel level) {
        job(id);
        return store.readLog(id, level);
    }

    public SseEmitter subscribe(String id, long after) {
        Job job = job(id);
        return bus.subscribeJob(id, job.eventsAfter(after, null), job.snapshot());
    }

    public SseEmitter subscribeAll() {
        return bus.subscribeGlobal(list());
    }

    private Job job(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new NoSuchElementException("No job " + id);
        }
        return job;
    }

    private void run(Job job) {
        if (job.isCancelRequested()) {
            return;
        }
        running.incrementAndGet();
        job.start();
        emit(job, JobEventLevel.SUMMARY, null, "실행 시작");
        bus.publishJob(job.snapshot());
        ExecutionManager manager = orchestration.manager();
        try {
            ExecutionReport report = manager.execute(job.request(), new Observer(job), job::isCancelRequested, job.id());
            if (!report.candidates().isEmpty()) {
                job.setDecision(report.chosenCandidate(), report.applied(), report.decisionNote());
            }
            job.finish(JobStatus.SUCCEEDED, null, report.finalContent());
            emit(job, JobEventLevel.SUMMARY, null, "성공");
        } catch (ModuleExecutionException e) {
            JobStatus status = switch (e.kind()) {
                case TIMEOUT -> JobStatus.TIMEOUT;
                case CANCELLED -> JobStatus.CANCELLED;
                case FAILED -> JobStatus.FAILED;
            };
            job.finish(status, e.getMessage(), null);
            emit(job, JobEventLevel.SUMMARY, null, statusLabel(status) + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Job {} crashed", job.id(), e);
            job.finish(JobStatus.FAILED, e.toString(), null);
            emit(job, JobEventLevel.SUMMARY, null, "실패: " + e);
        } finally {
            running.decrementAndGet();
            futures.remove(job.id());
            store.save(job.snapshot());
            store.close(job.id());
            bus.publishJob(job.snapshot());
            bus.completeJob(job.id());
            prune();
        }
    }

    private static String statusLabel(JobStatus status) {
        return switch (status) {
            case TIMEOUT -> "시간 초과";
            case CANCELLED -> "취소됨";
            case FAILED -> "실패";
            case SUCCEEDED -> "성공";
            case QUEUED -> "대기";
            case RUNNING -> "실행 중";
        };
    }

    private void emit(Job job, JobEventLevel level, String stepId, String message) {
        JobEvent event;
        // Parallel agents log concurrently; keep the file in the same order as memory so a
        // restart (which sorts by timestamp, stable) restores the exact sequence even when two
        // events share a timestamp (Windows clock granularity made this flaky).
        synchronized (job) {
            event = job.addEvent(level, stepId, message);
            store.append(event);
        }
        bus.publishLog(event);
    }

    @PreDestroy
    void shutdown() {
        bus.shutdown();
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Maps core execution events onto the job's steps and logs. */
    private final class Observer implements ExecutionObserver {
        private final Job job;

        Observer(Job job) {
            this.job = job;
        }

        @Override
        public void onPlan(List<ExecutionStep> steps) {
            job.setPlan(steps.stream().map(step -> new JobStep(step.id(), step.label(), step.moduleName(), step.role(),
                    step.stage(), step.candidate(), step.dependsOn(), StepStatus.PENDING, null, null, null)).toList());
            store.save(job.snapshot());
            bus.publishJob(job.snapshot());
        }

        @Override
        public void onStepStarted(ExecutionStep step) {
            job.relabelStepModule(step.id(), step.moduleName());
            job.updateStep(step.id(), StepStatus.RUNNING, null);
            bus.publishJob(job.snapshot());
        }

        @Override
        public void onStepFinished(ExecutionStep step, ExecutionResult result) {
            if (result != null) {
                job.addUsage(result.moduleName(), result.usage());
                emit(job, JobEventLevel.DETAIL, step.id(), "--- " + result.label() + " 결과 ---");
                for (String line : result.content().split("\\R")) {
                    emit(job, JobEventLevel.DETAIL, step.id(), line);
                }
            }
            job.updateStep(step.id(), StepStatus.DONE, null);
            store.save(job.snapshot());
            bus.publishJob(job.snapshot());
        }

        @Override
        public void onCandidate(ExecutionStep step, CandidatePatch patch, ExecutionResult result) {
            job.addCandidate(JobCandidate.from(patch, result.label(), step.id()));
            store.save(job.snapshot());
            bus.publishJob(job.snapshot());
        }

        @Override
        public void onStepFailed(ExecutionStep step, Throwable error) {
            job.updateStep(step.id(), StepStatus.FAILED, error.getMessage());
            emit(job, JobEventLevel.SUMMARY, step.id(), "단계 실패: " + error.getMessage());
            bus.publishJob(job.snapshot());
        }

        @Override
        public void onSummary(ExecutionStep step, String line) {
            emit(job, JobEventLevel.SUMMARY, step.isSystem() ? null : step.id(), line);
        }

        @Override
        public void onRateLimit(ExecutionStep step, dev.orchestrator.domain.RateLimitInfo info) {
            rateLimitListener.accept(info);
        }

        @Override
        public void onDetail(ExecutionStep step, String line) {
            for (String part : line.split("\\R")) {
                emit(job, JobEventLevel.DETAIL, step.isSystem() ? null : step.id(), part);
            }
        }
    }
}
