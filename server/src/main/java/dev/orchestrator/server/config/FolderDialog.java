package dev.orchestrator.server.config;

import dev.orchestrator.module.ProcessRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Opens the operating system's "choose a folder" dialog on the machine the server
 * runs on (this is a local tool, so that is the user's desktop) and returns the
 * chosen path. A browser cannot hand a page a real directory path, which is why
 * the dialog runs server-side.
 *
 * <p>Backends: Windows → PowerShell {@code FolderBrowserDialog}; WSL → the same
 * dialog through {@code powershell.exe} interop with the result mapped by
 * {@code wslpath}; Linux → {@code zenity} or {@code kdialog}; macOS → {@code osascript}.
 */
@Component
public class FolderDialog {
    static final long TIMEOUT_MINUTES = 5;

    public enum Backend { WINDOWS, WSL, ZENITY, KDIALOG, MACOS, NONE }

    private final Backend backend;

    public FolderDialog() {
        this(detect());
    }

    FolderDialog(Backend backend) {
        this.backend = backend;
    }

    public Backend backend() {
        return backend;
    }

    public boolean available() {
        return backend != Backend.NONE;
    }

    static Backend detect() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return Backend.WINDOWS;
        }
        if (os.contains("mac")) {
            return Backend.MACOS;
        }
        if (isWsl() && ProcessRunner.isOnPath("powershell.exe") && ProcessRunner.isOnPath("wslpath")) {
            return Backend.WSL;
        }
        if (ProcessRunner.isOnPath("zenity")) {
            return Backend.ZENITY;
        }
        if (ProcessRunner.isOnPath("kdialog")) {
            return Backend.KDIALOG;
        }
        return Backend.NONE;
    }

    static boolean isWsl() {
        try {
            Path version = Path.of("/proc/version");
            return Files.isRegularFile(version) && Files.readString(version).toLowerCase(java.util.Locale.ROOT).contains("microsoft");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Shows the dialog and blocks until the user picks or cancels.
     *
     * @param initial folder to start in (may be null or not exist)
     * @return the chosen path, empty when cancelled
     * @throws IllegalStateException when no dialog backend exists on this machine
     */
    public Optional<String> pick(String initial) throws IOException, InterruptedException {
        if (backend == Backend.NONE) {
            throw new IllegalStateException("이 서버가 도는 환경에서는 폴더 대화상자를 열 수 없습니다 (Windows, WSL+powershell.exe, zenity/kdialog, macOS만 지원). 경로를 직접 입력하세요.");
        }
        String start = initial == null ? "" : initial.strip();
        if (backend == Backend.WSL && !start.isEmpty()) {
            start = run(List.of("wslpath", "-w", start), 5).orElse("");   // dialog wants a Windows path
        }
        List<String> command = command(backend, start);
        Optional<String> out = run(command, TIMEOUT_MINUTES * 60);
        if (out.isEmpty() || out.get().isBlank()) {
            return Optional.empty();
        }
        String chosen = out.get().strip();
        if (backend == Backend.WSL) {
            chosen = run(List.of("wslpath", "-u", chosen), 5).orElse(chosen).strip();
        }
        return Optional.of(chosen);
    }

    /** The exact command per backend; the picked path is the last non-empty stdout line. */
    static List<String> command(Backend backend, String start) {
        return switch (backend) {
            case WINDOWS, WSL -> List.of(backend == Backend.WINDOWS ? "powershell" : "powershell.exe", "-NoProfile", "-STA", "-NonInteractive", "-Command", powershellScript(start));
            case ZENITY -> start.isEmpty()
                    ? List.of("zenity", "--file-selection", "--directory", "--title=작업 공간 선택")
                    : List.of("zenity", "--file-selection", "--directory", "--title=작업 공간 선택", "--filename=" + (start.endsWith("/") ? start : start + "/"));
            case KDIALOG -> List.of("kdialog", "--getexistingdirectory", start.isEmpty() ? "." : start, "--title", "작업 공간 선택");
            case MACOS -> List.of("osascript", "-e", "POSIX path of (choose folder with prompt \"작업 공간 선택\""
                    + (start.isEmpty() ? "" : " default location POSIX file \"" + start.replace("\"", "\\\"") + "\"") + ")");
            case NONE -> throw new IllegalStateException("no backend");
        };
    }

    /** FolderBrowserDialog on an STA thread, forced to the front of the browser window; prints the path or nothing. */
    static String powershellScript(String start) {
        String escaped = start.replace("'", "''");
        return "Add-Type -AssemblyName System.Windows.Forms; "
                + "$d = New-Object System.Windows.Forms.FolderBrowserDialog; "
                + "$d.Description = 'AI CLI Orchestrator 작업 공간 (AI가 읽고 수정하는 프로젝트 폴더)'; "
                + "$d.ShowNewFolderButton = $true; "
                + (escaped.isEmpty() ? "" : "if (Test-Path -LiteralPath '" + escaped + "') { $d.SelectedPath = '" + escaped + "' }; ")
                + "$owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true; ShowInTaskbar = $false; Opacity = 0 }; "
                + "$owner.Show(); "
                + "if ($d.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.WriteLine($d.SelectedPath) }; "
                + "$owner.Close()";
    }

    private static Optional<String> run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String last = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    last = line;
                }
            }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return Optional.empty();
        }
        return process.exitValue() == 0 ? Optional.ofNullable(last) : Optional.empty();   // cancel exits 1 on zenity/osascript
    }
}
