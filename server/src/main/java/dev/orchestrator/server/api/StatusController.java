package dev.orchestrator.server.api;

import dev.orchestrator.module.ProcessRunner;
import dev.orchestrator.server.config.OrchestrationService;
import dev.orchestrator.server.status.ClaudeStatusService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CLI status plus the two things a user can do about it from the dashboard: log in, re-check. */
@RestController
@RequestMapping("/api/status")
public class StatusController {
    private final ClaudeStatusService status;
    private final OrchestrationService orchestration;
    private final dev.orchestrator.server.job.JobService jobs;
    private final java.util.concurrent.atomic.AtomicBoolean watching = new java.util.concurrent.atomic.AtomicBoolean();

    public StatusController(ClaudeStatusService status, OrchestrationService orchestration, dev.orchestrator.server.job.JobService jobs) {
        this.status = status;
        this.orchestration = orchestration;
        this.jobs = jobs;
    }

    /** Drops the cached probe (and rebuilds AUTO modules) so a fresh install or login shows up now, not in a minute. */
    @PostMapping("/refresh")
    public ClaudeStatusService.StatusReport refresh() {
        status.invalidate();
        return status.report();
    }

    /**
     * Opens a terminal window on this machine running {@code claude auth login}: the login is an
     * OAuth flow in the browser that the CLI drives, which a headless server cannot complete itself.
     */
    @PostMapping("/login")
    public Map<String, Object> login() throws IOException {
        String configured = orchestration.settings().module("claude").command();
        String command = configured == null || configured.isBlank() ? "claude" : configured;
        Path resolved = ProcessRunner.resolveExecutable(command).orElse(null);
        if (resolved == null) {
            throw new IllegalStateException("claude 실행 파일을 찾지 못해 로그인 창을 열 수 없습니다. Claude Code를 먼저 설치하거나 서버 설정의 '실행 파일'에 경로를 넣으세요.");
        }
        List<String> launch = terminalCommand(resolved.toString());
        new ProcessBuilder(launch).start();
        watchForLogin();
        return Map.of("opened", true, "command", resolved + " auth login", "terminal", launch.get(0));
    }

    /**
     * After the terminal opens, re-probe {@code claude auth status} every 2s for up to 5 minutes and,
     * the moment it says logged in, push a {@code status} event to every open dashboard so the tile
     * flips without a refresh.
     */
    private void watchForLogin() {
        if (!watching.compareAndSet(false, true)) {
            return;   // one watcher is enough
        }
        Thread t = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 5 * 60_000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(2_000);
                    status.invalidate();
                    ClaudeStatusService.StatusReport report = status.report();
                    boolean loggedIn = report.modules().stream().anyMatch(m -> "claude".equals(m.name()) && Boolean.TRUE.equals(m.loggedIn()));
                    if (loggedIn) {
                        jobs.broadcast("status", report);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                watching.set(false);
            }
        }, "claude-login-watch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Logs the CLI out ({@code claude auth logout}). This is the CLI's own login, shared with every
     * terminal on this machine, so the page asks for confirmation before calling this.
     */
    @PostMapping("/logout")
    public Map<String, Object> logout() throws IOException, InterruptedException {
        String configured = orchestration.settings().module("claude").command();
        String command = configured == null || configured.isBlank() ? "claude" : configured;
        Path resolved = ProcessRunner.resolveExecutable(command).orElse(null);
        if (resolved == null) {
            throw new IllegalStateException("claude 실행 파일을 찾지 못했습니다.");
        }
        Process process = new ProcessBuilder(ProcessRunner.launchCommand(List.of(resolved.toString(), "auth", "logout"))).redirectErrorStream(true).start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        }
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("claude auth logout이 30초 안에 끝나지 않았습니다.");
        }
        status.invalidate();
        ClaudeStatusService.StatusReport report = status.report();
        jobs.broadcast("status", report);
        boolean loggedOut = report.modules().stream().anyMatch(m -> "claude".equals(m.name()) && Boolean.FALSE.equals(m.loggedIn()));
        return Map.of("loggedOut", loggedOut, "exitCode", process.exitValue(), "output", output.length() > 500 ? output.substring(0, 500) : output);
    }

    /** A visible terminal per OS; the user finishes the flow there and closes it. */
    static List<String> terminalCommand(String claude) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd.exe", "/c", "start", "Claude 로그인", "cmd.exe", "/k", "\"" + claude + "\" auth login");
        }
        if (isWsl()) {
            return List.of("cmd.exe", "/c", "start", "Claude 로그인", "wsl.exe", "-e", claude, "auth", "login");
        }
        if (os.contains("mac")) {
            return List.of("osascript", "-e", "tell application \"Terminal\" to do script \"" + claude.replace("\"", "\\\"") + " auth login\"", "-e", "tell application \"Terminal\" to activate");
        }
        for (String term : List.of("x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "xterm")) {
            if (ProcessRunner.isOnPath(term)) {
                return "gnome-terminal".equals(term) ? List.of(term, "--", claude, "auth", "login") : List.of(term, "-e", claude + " auth login");
            }
        }
        throw new IllegalStateException("터미널을 찾지 못했습니다. 셸에서 직접 실행하세요: " + claude + " auth login");
    }

    static boolean isWsl() {
        try {
            Path v = Path.of("/proc/version");
            return Files.isRegularFile(v) && Files.readString(v).toLowerCase(java.util.Locale.ROOT).contains("microsoft");
        } catch (IOException e) {
            return false;
        }
    }
}
