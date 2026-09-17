package dev.orchestrator.server.api;

import dev.orchestrator.server.job.DashboardService;
import dev.orchestrator.server.status.ClaudeStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {
    private final DashboardService dashboard;
    private final ClaudeStatusService status;

    public DashboardController(DashboardService dashboard, ClaudeStatusService status) {
        this.dashboard = dashboard;
        this.status = status;
    }

    @GetMapping("/api/dashboard")
    public DashboardService.Dashboard dashboard() {
        return dashboard.build();
    }

    @GetMapping("/api/status")
    public ClaudeStatusService.StatusReport status() {
        return status.report();
    }
}
