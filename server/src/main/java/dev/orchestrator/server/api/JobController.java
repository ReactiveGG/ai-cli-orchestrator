package dev.orchestrator.server.api;

import dev.orchestrator.application.ExecutionStep;
import dev.orchestrator.server.job.JobEvent;
import dev.orchestrator.server.job.JobEventLevel;
import dev.orchestrator.server.job.JobRequest;
import dev.orchestrator.server.job.JobService;
import dev.orchestrator.server.job.JobSnapshot;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public List<JobSnapshot> list() {
        return jobs.list();
    }

    @PostMapping
    public JobSnapshot submit(@RequestBody JobRequest request) {
        return jobs.submit(request.toExecutionRequest());
    }

    /** Several commands at once; each becomes its own job in the queue. */
    @PostMapping("/batch")
    public List<JobSnapshot> submitBatch(@RequestBody List<JobRequest> requests) {
        return jobs.submitAll(requests.stream().map(JobRequest::toExecutionRequest).toList());
    }

    /** The step graph a request would produce, without running it. */
    @PostMapping("/preview")
    public List<ExecutionStep> preview(@RequestBody JobRequest request) {
        return jobs.preview(request.toExecutionRequest());
    }

    @GetMapping("/{id}")
    public JobSnapshot get(@PathVariable String id) {
        return jobs.get(id);
    }

    @PostMapping("/{id}/cancel")
    public JobSnapshot cancel(@PathVariable String id) {
        return jobs.cancel(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) throws IOException {
        jobs.delete(id);
    }

    /** Applies one candidate's patch to the workspace by hand (also overrides the verifier's choice). */
    @PostMapping("/{id}/apply")
    public JobSnapshot apply(@PathVariable String id, @RequestParam int candidate) {
        return jobs.applyCandidate(id, candidate);
    }

    /** The saved diff of one competing candidate. */
    @GetMapping(value = "/{id}/candidates/{index}/patch", produces = MediaType.TEXT_PLAIN_VALUE)
    public String candidatePatch(@PathVariable String id, @PathVariable int index) {
        return jobs.candidatePatch(id, index);
    }

    @GetMapping("/{id}/logs")
    public List<JobEvent> logs(
            @PathVariable String id,
            @RequestParam(defaultValue = "SUMMARY") JobEventLevel level,
            @RequestParam(defaultValue = "0") long after
    ) {
        return jobs.events(id, after, level);
    }

    /** Raw log file lines (complete history even when in-memory events were trimmed). */
    @GetMapping(value = "/{id}/logs/file", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logFile(@PathVariable String id, @RequestParam(defaultValue = "DETAIL") JobEventLevel level) {
        return String.join("\n", jobs.logLines(id, level));
    }

    /** SSE: replays events after {@code after}, then streams live {@code log} and {@code job} events. */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        return jobs.subscribe(id, after);
    }
}
