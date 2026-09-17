package dev.orchestrator.server.job;

import dev.orchestrator.application.ExecutionManager;
import dev.orchestrator.application.ExecutionObserver;
import dev.orchestrator.application.ExecutionStep;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private final int concurrency;

    @Autowired
    public JobService(OrchestrationService orchestration, OrchestratorProperties properties) throws IOException {
        this(orchestration, new JobStore(properties.jobsDir()), properties.concurrency());
    }

    JobService(OrchestrationService orchestration, JobStore store, int concurrency) {
        this.orchestration = orchestration;
        this.store = store;
        this.concurrency = Math.max(1, concurrency);
        this.executor = Executors.newFixedThreadPool(this.concurrency, runnable -> {
            Thread thread = new Thread(runnable, "job-runner");
            thread.setDaemon(true);
            return thread;
        });
        for (JobSnapshot snapshot : store.loadAll()) {
            Job job = Job.fromSnapshot(snapshot);
            if (!snapshot.status().isTerminal()) {
                job.finish(JobStatus.FAILED, "서버가 재시작되어 중단됨", null);
                store.save(job.snapshot());
            }
            jobs.put(job.id(), job);
        }
        log.info("Loaded {} persisted jobs, concurrency {}", jobs.size(), this.concurrency);
    }

    public int concurrency() {
        return concurrency;
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
        job.addEvent(JobEventLevel.SUMMARY, null, "대기열에 추가됨: " + JobRequest.display(request));
        store.save(job.snapshot());
        bus.publishJob(job.snapshot());
        futures.put(id, executor.submit(() -> run(job)));
        return job.snapshot();
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
            job.addEvent(JobEventLevel.SUMMARY, null, "대기 중 취소됨");
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
        JobEvent event = job.addEvent(level, stepId, message);
        store.append(event);
        bus.publishLog(event);
    }

    @PreDestroy
    void shutdown() {
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
                    step.stage(), step.dependsOn(), StepStatus.PENDING, null, null, null)).toList());
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
        public void onDetail(ExecutionStep step, String line) {
            for (String part : line.split("\\R")) {
                emit(job, JobEventLevel.DETAIL, step.isSystem() ? null : step.id(), part);
            }
        }
    }
}
