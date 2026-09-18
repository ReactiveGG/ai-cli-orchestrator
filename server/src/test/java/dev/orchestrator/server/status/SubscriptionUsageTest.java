package dev.orchestrator.server.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.domain.RateLimitInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubscriptionUsageTest {
    @TempDir
    Path tempDir;

    @Test
    void remembersTheLatestReportAcrossRestarts() {
        Path file = tempDir.resolve("data").resolve("subscription-usage.json");
        SubscriptionUsage first = new SubscriptionUsage(file);
        assertNull(first.latest(), "nothing observed yet");

        Instant reset = Instant.parse("2026-09-18T12:00:00Z");
        first.record(new RateLimitInfo("allowed", 0.12, reset, 0.4, null, null, Instant.parse("2026-09-18T09:00:00Z")));
        first.record(new RateLimitInfo("allowed_warning", 0.9, reset, 0.41, null, "five_hour", Instant.parse("2026-09-18T09:05:00Z")));
        assertTrue(Files.isRegularFile(file));

        SubscriptionUsage reloaded = new SubscriptionUsage(file);
        assertEquals(0.9, reloaded.latest().fiveHourUtilization(), 1e-9);
        assertEquals("allowed_warning", reloaded.latest().status());
        assertEquals(reset, reloaded.latest().fiveHourResetsAt());
        assertEquals(Instant.parse("2026-09-18T09:05:00Z"), reloaded.latest().observedAt());
    }
}
