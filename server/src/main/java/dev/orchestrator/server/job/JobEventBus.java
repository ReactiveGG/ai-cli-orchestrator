package dev.orchestrator.server.job;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans job events out to SSE subscribers: per-job streams (log lines and step
 * changes) and one global stream (job snapshots for the dashboard).
 */
public final class JobEventBus {
    private static final long TIMEOUT_MS = 6L * 60 * 60 * 1000;

    private final Map<String, List<SseEmitter>> perJob = new ConcurrentHashMap<>();
    private final List<SseEmitter> global = new CopyOnWriteArrayList<>();

    public SseEmitter subscribeJob(String jobId, List<JobEvent> replay, JobSnapshot current) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = perJob.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("job").data(current));
            for (JobEvent event : replay) {
                emitter.send(SseEmitter.event().name("log").id(Long.toString(event.seq())).data(event));
            }
        } catch (IOException | IllegalStateException e) {
            remove.run();
        }
        return emitter;
    }

    public SseEmitter subscribeGlobal(List<JobSnapshot> current) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        global.add(emitter);
        Runnable remove = () -> global.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("jobs").data(current));
        } catch (IOException | IllegalStateException e) {
            remove.run();
        }
        return emitter;
    }

    public void publishLog(JobEvent event) {
        List<SseEmitter> list = perJob.get(event.jobId());
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("log").id(Long.toString(event.seq())).data(event));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    public void publishJob(JobSnapshot snapshot) {
        List<SseEmitter> list = perJob.get(snapshot.id());
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("job").data(snapshot));
                } catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        }
        for (SseEmitter emitter : global) {
            try {
                emitter.send(SseEmitter.event().name("job").data(snapshot));
            } catch (IOException | IllegalStateException e) {
                global.remove(emitter);
            }
        }
    }

    public void completeJob(String jobId) {
        List<SseEmitter> list = perJob.remove(jobId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }
}
