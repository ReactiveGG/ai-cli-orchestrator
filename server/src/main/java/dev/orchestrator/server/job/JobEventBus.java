package dev.orchestrator.server.job;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans job events out to SSE subscribers: per-job streams (log lines and step
 * changes) and one global stream (job snapshots for the dashboard).
 */
public final class JobEventBus {
    private static final long TIMEOUT_MS = 6L * 60 * 60 * 1000;
    /** Ping interval; the web client treats a stream silent for 3x this as dead and reopens it. */
    static final long HEARTBEAT_SECONDS = 15;

    private final Map<String, List<SseEmitter>> perJob = new ConcurrentHashMap<>();
    private final List<SseEmitter> global = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public JobEventBus() {
        heartbeat.scheduleAtFixedRate(this::ping, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Sends a {@code ping} event on every open stream so a client behind a proxy or
     * port relay (which may keep a dead server's socket open) can notice silence;
     * a failed send also evicts emitters whose client went away without a FIN.
     */
    void ping() {
        for (SseEmitter emitter : global) {
            if (!send(emitter, SseEmitter.event().name("ping").data("1"))) {
                global.remove(emitter);
            }
        }
        for (List<SseEmitter> list : perJob.values()) {
            for (SseEmitter emitter : list) {
                if (!send(emitter, SseEmitter.event().name("ping").data("1"))) {
                    list.remove(emitter);
                }
            }
        }
    }

    private static boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public void shutdown() {
        heartbeat.shutdownNow();
    }

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

    /** Pushes a fresh full list to every dashboard/list subscriber (after pruning, when jobs vanish without a job event). */
    public void publishJobs(List<JobSnapshot> current) {
        for (SseEmitter emitter : global) {
            if (!send(emitter, SseEmitter.event().name("jobs").data(current))) {
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
