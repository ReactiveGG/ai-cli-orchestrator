package dev.orchestrator.server.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.ExecutionRequest;
import dev.orchestrator.module.ModuleMode;
import dev.orchestrator.server.config.OrchestrationService;
import dev.orchestrator.server.config.OrchestratorProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobServiceTest {
    @TempDir
    Path tempDir;

    private JobService service;
    private OrchestrationService orchestration;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // the workspace must be a git repo for presets with several coders (best-of-3)
        Process git = new ProcessBuilder("git", "init", "-q").directory(tempDir.toFile()).redirectErrorStream(true).start();
        git.getInputStream().readAllBytes();
        git.waitFor();
        OrchestratorProperties properties = new OrchestratorProperties(
                tempDir, tempDir, 1, Duration.ofSeconds(30), Duration.ofSeconds(5),
                Map.of(
                        "claude", new OrchestratorProperties.ModuleSettings(ModuleMode.STUB, "claude", List.of(), null, null, List.of()),
                        "codex", new OrchestratorProperties.ModuleSettings(ModuleMode.STUB, "codex", List.of(), null, null, List.of())),
                new OrchestratorProperties.Status("", Duration.ofSeconds(60)),
                new OrchestratorProperties.Isolation(true, List.of(), true, false, 40_000, List.of("__pycache__")),
                new OrchestratorProperties.Security(List.of(tempDir.toString()), false, null),
                new OrchestratorProperties.Retention(200, Duration.ofDays(30)),
                new OrchestratorProperties.Prompt(24_000, 60_000));
        orchestration = new OrchestrationService(properties);
        service = new JobService(orchestration, new JobStore(properties.jobsDir()), 1);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static JobSnapshot await(JobService service, String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            JobSnapshot snapshot = service.get(id);
            if (snapshot.status().isTerminal()) {
                return snapshot;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Job " + id + " did not finish");
    }

    @Test
    void runsCrossReviewPresetAndPersists() throws Exception {
        JobSnapshot submitted = service.submit(new ExecutionRequest("cross-review", "loginService.ts", List.of(), "ko"));
        assertEquals(JobStatus.QUEUED, submitted.status());
        assertEquals("교차 리뷰", submitted.flowLabel());

        JobSnapshot done = await(service, submitted.id());

        assertEquals(JobStatus.SUCCEEDED, done.status());
        assertEquals(List.of("s1/planner@claude", "s2/coder@claude", "s3/reviewer@claude", "s3/reviewer@claude#1", "s4/verifier@claude"),
                done.steps().stream().map(JobStep::id).toList());
        assertEquals(List.of(1, 2, 3, 3, 4), done.steps().stream().map(JobStep::stage).toList());
        assertTrue(done.steps().stream().allMatch(step -> step.status() == StepStatus.DONE));
        assertEquals(100, done.progressPercent());
        assertTrue(done.usage().totalTokens() > 0);
        assertEquals(1, done.usageByModule().size());
        assertNotNull(done.result());

        List<JobEvent> summary = service.events(done.id(), 0, JobEventLevel.SUMMARY);
        List<JobEvent> detail = service.events(done.id(), 0, JobEventLevel.DETAIL);
        assertTrue(summary.stream().anyMatch(e -> e.message().contains("프리셋 교차 리뷰 1-1-2-1") && e.stepId() == null));
        assertTrue(detail.stream().anyMatch(e -> e.message().contains("Target: loginService.ts")));
        assertTrue(Files.isRegularFile(tempDir.resolve("jobs").resolve(done.id()).resolve("job.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve("jobs").resolve(done.id()).resolve("detail.log")));
    }

    @Test
    void reloadedPresetsApplyToNewJobsAndDefaultPresetFallsBack() throws Exception {
        FlowConfig custom = new FlowConfig(Map.of("mine", dev.orchestrator.domain.FlowDefinition.preset("mine", "내 프리셋", "codex", List.of())), Map.of(), Map.of());
        orchestration.reload(custom);

        JobSnapshot done = await(service, service.submit(new ExecutionRequest("default", "jwt refresh", List.of(), "ko")).id());

        assertEquals(JobStatus.SUCCEEDED, done.status());
        assertEquals("mine", done.flow());
        assertEquals(List.of("s1/planner@codex", "s2/coder@codex", "s3/reviewer@codex", "s4/verifier@codex"),
                done.steps().stream().map(JobStep::id).toList());
        assertEquals(List.of("s2/coder@codex"), done.steps().get(2).dependsOn());
        assertEquals(List.of(), done.steps().get(0).dependsOn());
    }

    @Test
    void queuedJobCanBeCancelledAndCommandLineIsParsed() throws Exception {
        List<JobSnapshot> batch = service.submitAll(List.of(
                new JobRequest("auth.ts --focus security --focus arch", null, null, null, null).toExecutionRequest(),
                new JobRequest("run 'src/some dir' --preset best-of-3", null, null, null, null).toExecutionRequest(),
                new JobRequest(null, "cross-review", "x.ts", List.of(), "en").toExecutionRequest()));
        assertEquals(3, batch.size());
        assertEquals("auth.ts --focus security --focus arch", batch.get(0).command());
        assertEquals("default", batch.get(0).flow());
        assertEquals("\"src/some dir\" --preset best-of-3", batch.get(1).command());
        assertEquals("x.ts --preset cross-review --language en", batch.get(2).command());

        JobSnapshot cancelled = service.cancel(batch.get(2).id());
        assertEquals(JobStatus.CANCELLED, cancelled.status());

        assertEquals(JobStatus.SUCCEEDED, await(service, batch.get(0).id()).status());
        assertEquals(JobStatus.SUCCEEDED, await(service, batch.get(1).id()).status());
        assertEquals(JobStatus.CANCELLED, service.get(batch.get(2).id()).status());
    }

    @Test
    void bestOfThreeRecordsCandidatesAndDecisionInSnapshot() throws Exception {
        JobSnapshot done = await(service, service.submit(new ExecutionRequest("best-of-3", "feature", List.of(), "ko")).id());

        assertEquals(JobStatus.SUCCEEDED, done.status());
        assertEquals(List.of(1, 2, 3), done.candidates().stream().map(JobCandidate::index).toList());
        assertEquals(List.of(1, 2, 3), done.steps().stream().filter(s -> s.stage() == 2).map(JobStep::candidate).toList());
        assertTrue(done.candidates().stream().allMatch(JobCandidate::empty), "stub coders change nothing");
        assertEquals(0, done.chosenCandidate(), "stub verifier gives no decision");
        assertFalse(done.applied());
        assertTrue(done.decisionNote().contains("판독하지 못했습니다"), done.decisionNote());
        assertTrue(done.result().endsWith("> " + done.decisionNote()));
        assertEquals("", service.candidatePatch(done.id(), 2));
        assertThrows(IllegalStateException.class, () -> service.applyCandidate(done.id(), 2), "empty candidate cannot be applied");
        assertThrows(IllegalArgumentException.class, () -> service.applyCandidate(done.id(), 9));
        assertTrue(Files.isRegularFile(tempDir.resolve("jobs").resolve(done.id()).resolve("candidates").resolve("c3.patch")));
        assertFalse(Files.exists(tempDir.resolve("worktrees").resolve(done.id())), "worktrees cleaned up");

        List<JobEvent> summaryBefore = service.events(done.id(), 0, JobEventLevel.SUMMARY);
        List<JobEvent> detailBefore = service.events(done.id(), 0, JobEventLevel.DETAIL);
        JobService reloaded = new JobService(orchestration, new JobStore(tempDir.resolve("jobs")), 1);
        assertEquals(3, reloaded.get(done.id()).candidates().size(), "candidates survive a restart");
        List<JobEvent> summaryAfter = reloaded.events(done.id(), 0, JobEventLevel.SUMMARY);
        List<JobEvent> detailAfter = reloaded.events(done.id(), 0, JobEventLevel.DETAIL);
        assertEquals(summaryBefore.stream().map(JobEvent::message).toList(), summaryAfter.stream().map(JobEvent::message).toList(), "summary log restored from disk");
        assertEquals(detailBefore.size(), detailAfter.size(), "detail log restored from disk");
        assertEquals(summaryBefore.stream().map(JobEvent::stepId).toList(), summaryAfter.stream().map(JobEvent::stepId).toList());
        assertTrue(summaryAfter.stream().mapToLong(JobEvent::seq).allMatch(seq -> seq > 0));
        reloaded.shutdown();
    }

    @Test
    void pruneKeepsOnlyTheNewestFinishedJobs() throws Exception {
        JobService keepTwo = new JobService(orchestration, new JobStore(tempDir.resolve("jobs")), 1, new OrchestratorProperties.Retention(2, Duration.ZERO));
        try {
            String a = await(keepTwo, keepTwo.submit(new ExecutionRequest("default", "a", List.of(), "ko")).id()).id();
            Thread.sleep(5);
            String b = await(keepTwo, keepTwo.submit(new ExecutionRequest("default", "b", List.of(), "ko")).id()).id();
            Thread.sleep(5);
            String c = await(keepTwo, keepTwo.submit(new ExecutionRequest("default", "c", List.of(), "ko")).id()).id();
            assertEquals(List.of(c, b), keepTwo.list().stream().map(JobSnapshot::id).toList(), "oldest finished job pruned after c finished");
            assertFalse(Files.exists(tempDir.resolve("jobs").resolve(a)), "its directory is gone");
            assertTrue(Files.exists(tempDir.resolve("jobs").resolve(c)));
            assertEquals(0, keepTwo.prune(), "nothing more to prune");

            // age-based: everything finished before "now" goes, but a restart with the same limits keeps the survivors
            JobService reloaded = new JobService(orchestration, new JobStore(tempDir.resolve("jobs")), 1, new OrchestratorProperties.Retention(2, Duration.ofDays(30)));
            assertEquals(2, reloaded.list().size());
            reloaded.shutdown();
            JobService ageOnly = new JobService(orchestration, new JobStore(tempDir.resolve("jobs")), 1, new OrchestratorProperties.Retention(0, Duration.ofMillis(1)));
            assertEquals(0, ageOnly.list().size(), "both older than 1ms at startup");
            ageOnly.shutdown();
        } finally {
            keepTwo.shutdown();
        }
    }

    @Test
    void listFiltersAndPages() throws Exception {
        await(service, service.submit(new ExecutionRequest("default", "alpha.ts", List.of(), "ko")).id());
        await(service, service.submit(new ExecutionRequest("cross-review", "beta.ts", List.of(), "ko")).id());
        await(service, service.submit(new ExecutionRequest("default", "gamma.ts", List.of(), "ko")).id());
        assertEquals(3, service.count(null, null));
        assertEquals(List.of("gamma.ts", "beta.ts", "alpha.ts"), service.list(null, null, 0, 0).stream().map(JobSnapshot::target).toList(), "newest first");
        assertEquals(List.of("beta.ts"), service.list("BETA", null, 0, 0).stream().map(JobSnapshot::target).toList(), "case-insensitive command match");
        assertEquals(List.of("beta.ts"), service.list("교차", null, 0, 0).stream().map(JobSnapshot::target).toList(), "preset label match");
        assertEquals(List.of("beta.ts", "alpha.ts"), service.list(null, null, 1, 5).stream().map(JobSnapshot::target).toList(), "offset");
        assertEquals(List.of("gamma.ts"), service.list(null, JobStatus.SUCCEEDED, 0, 1).stream().map(JobSnapshot::target).toList(), "limit");
        assertEquals(0, service.count(null, JobStatus.FAILED));
    }

    @Test
    void unknownFlowIsRejectedAtSubmit() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(new ExecutionRequest("nope", "x", List.of(), "ko")));
    }
}
