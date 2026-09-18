package dev.orchestrator.server.config;

import dev.orchestrator.module.ProcessRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * What the current workspace can do: competition mode (2+ coders) needs the folder to be a
 * git repository and git to be installed. Ordinary users should not have to know that, so the
 * UI shows the state and offers "준비하기", which is {@link #gitInit()} here.
 */
@Component
public class WorkspaceService {
    /**
     * @param gitRepo      the workspace has a {@code .git} (competition mode possible)
     * @param gitAvailable a {@code git} executable was found (needed to prepare and to run competition mode)
     */
    public record WorkspaceStatus(String path, boolean exists, boolean gitRepo, boolean gitAvailable, String gitVersion, String gitCommand) {
    }

    private final RuntimeSettings settings;

    public WorkspaceService(RuntimeSettings settings) {
        this.settings = settings;
    }

    public WorkspaceStatus status() {
        Path ws = settings.workspace();
        Path git = ProcessRunner.resolveExecutable("git").orElse(null);
        String version = git == null ? null : run(List.of(git.toString(), "--version"), null).map(String::strip).orElse(null);
        return new WorkspaceStatus(ws == null ? null : ws.toString(), ws != null && Files.isDirectory(ws),
                ws != null && Files.exists(ws.resolve(".git")), git != null, version, git == null ? null : git.toString());
    }

    /**
     * {@code git init} in the workspace: creates the hidden {@code .git} folder, nothing more (no
     * commit, nothing uploaded). Idempotent: an existing repository is left as is.
     */
    public WorkspaceStatus gitInit() throws IOException {
        Path ws = settings.workspace();
        if (ws == null || !Files.isDirectory(ws)) {
            throw new IllegalStateException("작업 공간 폴더가 없습니다. 먼저 서버 설정에서 존재하는 폴더를 지정하고 저장하세요.");
        }
        if (!settings.isAllowedWorkspace(ws)) {
            throw new IllegalStateException("허용된 작업 공간 루트 밖입니다.");
        }
        if (Files.exists(ws.resolve(".git"))) {
            return status();
        }
        Path git = ProcessRunner.resolveExecutable("git").orElseThrow(() ->
                new IllegalStateException("git이 설치돼 있지 않습니다. Git for Windows(https://git-scm.com/download/win)를 설치한 뒤 다시 시도하세요."));
        String out = run(List.of(git.toString(), "init", "-q"), ws).orElse(null);
        if (!Files.exists(ws.resolve(".git"))) {
            throw new IllegalStateException("git init에 실패했습니다" + (out == null || out.isBlank() ? "" : ": " + out.strip()));
        }
        return status();
    }

    private static java.util.Optional<String> run(List<String> command, Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ProcessRunner.launchCommand(command)).redirectErrorStream(true);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            Process p = pb.start();
            String text;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                text = r.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(text);
        } catch (IOException e) {
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        }
    }
}
