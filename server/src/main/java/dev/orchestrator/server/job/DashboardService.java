package dev.orchestrator.server.job;

import dev.orchestrator.domain.TokenUsage;
import dev.orchestrator.server.status.ClaudeStatusService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    public record Usage(TokenUsage today, TokenUsage total, Map<String, TokenUsage> byModule, List<DailyUsage> last7Days) {
    }

    public record DailyUsage(String date, long inputTokens, long outputTokens, double costUsd, int jobs) {
    }

    public record Dashboard(
            Usage usage,
            ClaudeStatusService.StatusReport status,
            Map<JobStatus, Long> jobCounts,
            int concurrency,
            int running,
            List<JobSnapshot> recentJobs,
            Instant generatedAt,
            /** latest subscription windows from a rate_limit_event, null until a real Claude run reported one */
            dev.orchestrator.domain.RateLimitInfo subscription
    ) {
    }

    private final JobService jobs;
    private final ClaudeStatusService status;
    private final dev.orchestrator.server.status.SubscriptionUsage subscription;

    public DashboardService(JobService jobs, ClaudeStatusService status, dev.orchestrator.server.status.SubscriptionUsage subscription) {
        this.jobs = jobs;
        this.status = status;
        this.subscription = subscription;
    }

    public Dashboard build() {
        List<JobSnapshot> all = jobs.list();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);

        TokenUsage todayUsage = TokenUsage.ZERO;
        TokenUsage total = TokenUsage.ZERO;
        Map<String, TokenUsage> byModule = new LinkedHashMap<>();
        Map<LocalDate, long[]> daily = new LinkedHashMap<>();
        Map<LocalDate, double[]> dailyCost = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            daily.put(today.minusDays(i), new long[3]);
            dailyCost.put(today.minusDays(i), new double[1]);
        }
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (JobStatus s : JobStatus.values()) {
            counts.put(s, 0L);
        }

        for (JobSnapshot job : all) {
            counts.merge(job.status(), 1L, Long::sum);
            TokenUsage usage = job.usage() == null ? TokenUsage.ZERO : job.usage();
            total = total.plus(usage);
            if (job.usageByModule() != null) {
                job.usageByModule().forEach((module, u) -> byModule.merge(module, u, TokenUsage::plus));
            }
            LocalDate day = job.createdAt().atZone(zone).toLocalDate();
            if (day.equals(today)) {
                todayUsage = todayUsage.plus(usage);
            }
            long[] bucket = daily.get(day);
            if (bucket != null) {
                bucket[0] += usage.inputTokens();
                bucket[1] += usage.outputTokens();
                bucket[2] += 1;
                dailyCost.get(day)[0] += usage.costUsd();
            }
        }
        List<DailyUsage> last7 = daily.entrySet().stream()
                .map(e -> new DailyUsage(e.getKey().toString(), e.getValue()[0], e.getValue()[1],
                        dailyCost.get(e.getKey())[0], (int) e.getValue()[2]))
                .toList();

        return new Dashboard(
                new Usage(todayUsage, total, byModule, last7),
                status.report(),
                counts,
                jobs.concurrency(),
                jobs.runningCount(),
                all.stream().limit(10).toList(),
                Instant.now(),
                subscription.latest()
        );
    }
}
