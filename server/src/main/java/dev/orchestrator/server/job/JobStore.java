package dev.orchestrator.server.job;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists jobs under {@code <dataDir>/jobs/<id>/}: {@code job.json} (snapshot),
 * {@code summary.log} and {@code detail.log} (append-only, one event per line).
 */
public final class JobStore {
    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    private final Path jobsDir;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Writer> summaryWriters = new ConcurrentHashMap<>();
    private final Map<String, Writer> detailWriters = new ConcurrentHashMap<>();

    public JobStore(Path jobsDir) throws IOException {
        this.jobsDir = jobsDir;
        Files.createDirectories(jobsDir);
    }

    public Path dir(String jobId) {
        return jobsDir.resolve(jobId);
    }

    public void save(JobSnapshot snapshot) {
        try {
            Path dir = dir(snapshot.id());
            Files.createDirectories(dir);
            Path tmp = dir.resolve("job.json.tmp");
            mapper.writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, dir.resolve("job.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Cannot save job {}: {}", snapshot.id(), e.toString());
        }
    }

    public void append(JobEvent event) {
        Map<String, Writer> writers = event.level() == JobEventLevel.SUMMARY ? summaryWriters : detailWriters;
        String file = event.level() == JobEventLevel.SUMMARY ? "summary.log" : "detail.log";
        try {
            Writer writer = writers.computeIfAbsent(event.jobId(), id -> openWriter(dir(id).resolve(file)));
            synchronized (writer) {
                writer.write(TS.format(event.at()));
                writer.write(' ');
                writer.write(event.stepId() == null ? "-" : event.stepId());
                writer.write(' ');
                writer.write(event.message().replace("\r", "").replace("\n", "\\n"));
                writer.write('\n');
                if (event.level() == JobEventLevel.SUMMARY) {
                    writer.flush();
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            log.warn("Cannot append log for job {}: {}", event.jobId(), e.toString());
        }
    }

    public void close(String jobId) {
        for (Map<String, Writer> writers : List.of(summaryWriters, detailWriters)) {
            Writer writer = writers.remove(jobId);
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /**
     * Rebuilds a job's events from its log files (used when jobs are restored
     * after a restart). Lines are {@code <ISO timestamp> <stepId|-> <message>};
     * summary and detail lines are merged in timestamp order and re-numbered.
     * Only the newest {@link Job#MAX_EVENTS_IN_MEMORY} are kept.
     */
    public List<JobEvent> readEvents(String jobId) {
        List<JobEvent> events = new ArrayList<>();
        for (JobEventLevel level : JobEventLevel.values()) {
            for (String line : readLog(jobId, level)) {
                JobEvent parsed = parseLine(jobId, level, line);
                if (parsed != null) {
                    events.add(parsed);
                }
            }
        }
        events.sort(Comparator.comparing(JobEvent::at));
        if (events.size() > Job.MAX_EVENTS_IN_MEMORY) {
            events = new ArrayList<>(events.subList(events.size() - Job.MAX_EVENTS_IN_MEMORY, events.size()));
        }
        List<JobEvent> numbered = new ArrayList<>(events.size());
        long seq = 0;
        for (JobEvent e : events) {
            numbered.add(new JobEvent(++seq, e.at(), e.jobId(), e.level(), e.stepId(), e.message()));
        }
        return numbered;
    }

    private static JobEvent parseLine(String jobId, JobEventLevel level, String line) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            return null;
        }
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        String ts = line.substring(0, firstSpace);
        String stepId = secondSpace < 0 ? line.substring(firstSpace + 1) : line.substring(firstSpace + 1, secondSpace);
        String message = secondSpace < 0 ? "" : line.substring(secondSpace + 1).replace("\\n", "\n");
        java.time.Instant at;
        try {
            at = java.time.Instant.parse(ts);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
        return new JobEvent(0, at, jobId, level, "-".equals(stepId) ? null : stepId, message);
    }

    /** Reads a whole log file back (for jobs whose events fell out of memory). */
    public List<String> readLog(String jobId, JobEventLevel level) {
        Path file = dir(jobId).resolve(level == JobEventLevel.SUMMARY ? "summary.log" : "detail.log");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Cannot read log {}: {}", file, e.toString());
            return List.of();
        }
    }

    public void delete(String jobId) throws IOException {
        close(jobId);
        Path dir = dir(jobId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        // Windows may keep a just-closed log briefly locked (indexer, antivirus): retry a few times.
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                return;
            } catch (java.io.UncheckedIOException e) {
                last = e.getCause();
            } catch (IOException e) {
                last = e;
            }
            if (!Files.exists(dir)) {
                return;
            }
            try {
                Thread.sleep(100L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (Files.exists(dir) && last != null) {
            throw last;
        }
    }

    public List<JobSnapshot> loadAll() {
        List<JobSnapshot> snapshots = new ArrayList<>();
        if (!Files.isDirectory(jobsDir)) {
            return snapshots;
        }
        try (Stream<Path> dirs = Files.list(jobsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path json = dir.resolve("job.json");
                if (Files.isRegularFile(json)) {
                    try {
                        snapshots.add(mapper.readValue(json.toFile(), JobSnapshot.class));
                    } catch (IOException e) {
                        log.warn("Skipping unreadable job {}: {}", dir.getFileName(), e.toString());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Cannot list jobs dir {}: {}", jobsDir, e.toString());
        }
        snapshots.sort(Comparator.comparing(JobSnapshot::createdAt));
        return snapshots;
    }

    private Writer openWriter(Path file) {
        try {
            Files.createDirectories(file.getParent());
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
