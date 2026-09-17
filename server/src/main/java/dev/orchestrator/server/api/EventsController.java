package dev.orchestrator.server.api;

import dev.orchestrator.server.job.JobService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventsController {
    private final JobService jobs;

    public EventsController(JobService jobs) {
        this.jobs = jobs;
    }

    /** SSE: initial {@code jobs} list, then a {@code job} snapshot whenever any job changes. */
    @GetMapping(value = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return jobs.subscribeAll();
    }
}
