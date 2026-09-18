package dev.orchestrator.server.api;

import dev.orchestrator.server.config.RuntimeSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Directory listing for the in-page folder picker (the workspace field). A browser
 * cannot hand a page a real path, and native dialogs proved flaky behind the browser
 * window, so the page browses the server's disk instead — limited to the allowed
 * workspace roots, which is all a workspace may be anyway.
 */
@RestController
public class FileSystemController {
    public record Entry(String name, String path, boolean gitRepo) {
    }

    public record Listing(String path, String parent, List<Entry> dirs, List<Entry> roots, boolean allowed, String error) {
    }

    private final RuntimeSettings settings;

    public FileSystemController(RuntimeSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/api/fs/dirs")
    public Listing dirs(@RequestParam(required = false) String path) {
        List<Entry> roots = settings.allowedRoots().stream().map(r -> new Entry(r.toString(), r.toString(), isGitRepo(r))).toList();
        if (path == null || path.isBlank()) {
            Path start = roots.isEmpty() ? Path.of(System.getProperty("user.home")) : Path.of(roots.get(0).path());
            return list(start, roots);
        }
        return list(Path.of(path.trim()).toAbsolutePath().normalize(), roots);
    }

    private Listing list(Path dir, List<Entry> roots) {
        boolean allowed = settings.isAllowedWorkspace(dir);
        if (!allowed) {
            return new Listing(dir.toString(), parentOf(dir), List.of(), roots, false,
                    "허용된 작업 공간 루트 밖입니다. 루트를 바꾸려면 application.yml의 orchestrator.security.allowed-workspace-roots를 수정하세요.");
        }
        if (!Files.isDirectory(dir)) {
            return new Listing(dir.toString(), parentOf(dir), List.of(), roots, true, "폴더가 없습니다");
        }
        List<Entry> dirs = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith(".") || p.getFileName().toString().equals(".claude"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .limit(500)
                    .forEach(p -> dirs.add(new Entry(p.getFileName().toString(), p.toString(), isGitRepo(p))));
        } catch (IOException | SecurityException e) {
            return new Listing(dir.toString(), parentOf(dir), List.of(), roots, true, "읽을 수 없습니다: " + e.getMessage());
        }
        return new Listing(dir.toString(), parentOf(dir), dirs, roots, true, null);
    }

    private String parentOf(Path dir) {
        Path parent = dir.getParent();
        return parent != null && settings.isAllowedWorkspace(parent) ? parent.toString() : null;
    }

    private static boolean isGitRepo(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }
}
