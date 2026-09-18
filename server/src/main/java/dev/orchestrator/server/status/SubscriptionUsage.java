package dev.orchestrator.server.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.orchestrator.domain.RateLimitInfo;
import dev.orchestrator.server.config.OrchestratorProperties;
import dev.orchestrator.server.job.JobService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Latest subscription usage windows (5-hour / 7-day) seen in any job's
 * {@code rate_limit_event}, kept across restarts in {@code <data-dir>/subscription-usage.json}
 * so the dashboard can show them before the next job runs.
 */
@Component
public class SubscriptionUsage {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionUsage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private volatile RateLimitInfo latest;

    @org.springframework.beans.factory.annotation.Autowired
    public SubscriptionUsage(OrchestratorProperties properties, JobService jobs) {
        this(properties.dataDir().resolve("subscription-usage.json"));
        jobs.setRateLimitListener(this::record);
    }

    SubscriptionUsage(Path file) {
        this.file = file;
        this.latest = load();
    }

    public RateLimitInfo latest() {
        return latest;
    }

    public synchronized void record(RateLimitInfo info) {
        if (info == null) {
            return;
        }
        latest = info;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), info);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not persist subscription usage: {}", e.toString());
        }
    }

    private RateLimitInfo load() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), RateLimitInfo.class);
        } catch (IOException e) {
            log.warn("Ignoring unreadable {}: {}", file, e.toString());
            return null;
        }
    }
}
