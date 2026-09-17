package dev.orchestrator.server.config;

import dev.orchestrator.module.ModuleMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Settings the UI can change at runtime. Defaults come from
 * {@link OrchestratorProperties} (application.yml); overrides are persisted to
 * {@code <data-dir>/settings.yml} and win over the defaults on startup.
 */
@Component
public class RuntimeSettings {
    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);

    public record ModuleSnapshot(ModuleMode mode, String command, String model, Double maxBudgetUsd, List<String> allowedTools, List<String> extraArgs) {
    }

    public record IsolationSnapshot(boolean enabled, boolean autoApply, boolean keepWorktrees, int maxPatchChars, List<String> linkDirs, List<String> exclude) {
    }

    public record Snapshot(
            String workspace,
            int concurrency,
            long moduleTimeoutSeconds,
            long idleWarningSeconds,
            Map<String, ModuleSnapshot> modules,
            IsolationSnapshot isolation
    ) {
    }

    private final OrchestratorProperties defaults;
    private final Path file;
    private Snapshot current;

    public RuntimeSettings(OrchestratorProperties defaults) {
        this.defaults = defaults;
        this.file = defaults.dataDir().resolve("settings.yml");
        this.current = fromProperties(defaults);
        loadOverrides();
        if (!isAllowedWorkspace(workspace())) {
            log.warn("작업 공간 {} 이(가) 허용 루트 {} 밖에 있습니다. 화면에서 다른 경로로 바꿀 때 거부됩니다.", workspace(), allowedRoots());
        }
    }

    public synchronized Snapshot current() {
        return current;
    }

    public Path file() {
        return file;
    }

    /** Validates, stores and persists new settings. Callers apply them (manager rebuild, pool resize). */
    public synchronized Snapshot update(Snapshot next) {
        Snapshot validated = validate(next);
        this.current = validated;
        save();
        return validated;
    }

    // ---- convenience accessors used by the services --------------------------

    public Path workspace() {
        String ws = current().workspace();
        return ws == null || ws.isBlank() ? Path.of("").toAbsolutePath() : Path.of(ws);
    }

    public Duration moduleTimeout() {
        return Duration.ofSeconds(current().moduleTimeoutSeconds());
    }

    public Duration idleWarning() {
        return Duration.ofSeconds(current().idleWarningSeconds());
    }

    public ModuleSnapshot module(String name) {
        ModuleSnapshot m = current().modules().get(name);
        return m != null ? m : new ModuleSnapshot(ModuleMode.AUTO, name, null, null, List.of(), List.of());
    }

    public IsolationSnapshot isolation() {
        return current().isolation();
    }

    // ---- defaults / persistence ------------------------------------------------

    static Snapshot fromProperties(OrchestratorProperties p) {
        Map<String, ModuleSnapshot> modules = new LinkedHashMap<>();
        for (String name : List.of("claude", "codex")) {
            OrchestratorProperties.ModuleSettings m = p.moduleSettings(name);
            modules.put(name, new ModuleSnapshot(m.mode(), m.command() == null || m.command().isBlank() ? name : m.command(),
                    m.model(), m.maxBudgetUsd(), m.allowedTools() == null ? List.of() : m.allowedTools(), m.extraArgs() == null ? List.of() : m.extraArgs()));
        }
        OrchestratorProperties.Isolation iso = p.isolation();
        IsolationSnapshot isolation = iso == null
                ? new IsolationSnapshot(true, true, false, 40_000, List.of(), List.of())
                : new IsolationSnapshot(iso.enabled(), iso.autoApply(), iso.keepWorktrees(), iso.maxPatchChars(), iso.linkDirs(), iso.exclude());
        return new Snapshot(p.workspaceOrCwd().toString(), p.concurrency(), p.moduleTimeout().toSeconds(), p.idleWarning().toSeconds(), modules, isolation);
    }

    private Snapshot validate(Snapshot s) {
        if (s == null) {
            throw new IllegalArgumentException("settings body is required");
        }
        String workspace = s.workspace() == null || s.workspace().isBlank() ? current.workspace() : s.workspace().trim();
        if (!Files.isDirectory(Path.of(workspace))) {
            throw new IllegalArgumentException("작업 공간 디렉터리가 없습니다: " + workspace);
        }
        if (!isAllowedWorkspace(Path.of(workspace))) {
            throw new IllegalArgumentException("작업 공간은 허용된 루트 아래에 있어야 합니다: " + workspace
                    + " (허용 루트: " + allowedRoots() + ", orchestrator.security.allowed-workspace-roots 로 변경)");
        }
        if (s.concurrency() < 1 || s.concurrency() > 16) {
            throw new IllegalArgumentException("동시 실행 수는 1~16 사이여야 합니다");
        }
        if (s.moduleTimeoutSeconds() < 30) {
            throw new IllegalArgumentException("모듈 타임아웃은 30초 이상이어야 합니다");
        }
        if (s.idleWarningSeconds() < 5) {
            throw new IllegalArgumentException("유휴 경고는 5초 이상이어야 합니다");
        }
        Map<String, ModuleSnapshot> modules = new LinkedHashMap<>(current.modules());
        if (s.modules() != null) {
            s.modules().forEach((name, m) -> {
                ModuleSnapshot base = modules.getOrDefault(name, new ModuleSnapshot(ModuleMode.AUTO, name, null, null, List.of(), List.of()));
                modules.put(name, new ModuleSnapshot(
                        m.mode() == null ? base.mode() : m.mode(),
                        m.command() == null || m.command().isBlank() ? base.command() : m.command().trim(),
                        m.model() == null || m.model().isBlank() ? null : m.model().trim(),
                        m.maxBudgetUsd() == null || m.maxBudgetUsd() <= 0 ? null : m.maxBudgetUsd(),
                        clean(m.allowedTools()), clean(m.extraArgs())));
            });
        }
        IsolationSnapshot iso = s.isolation() == null ? current.isolation() : new IsolationSnapshot(
                s.isolation().enabled(), s.isolation().autoApply(), s.isolation().keepWorktrees(),
                Math.max(1_000, s.isolation().maxPatchChars()), clean(s.isolation().linkDirs()), clean(s.isolation().exclude()));
        return new Snapshot(workspace, s.concurrency(), s.moduleTimeoutSeconds(), s.idleWarningSeconds(), modules, iso);
    }

    /** Roots a workspace may live under; from application.yml, {@code ${user.home}} by default. */
    public List<Path> allowedRoots() {
        OrchestratorProperties.Security sec = defaults.security();
        List<Path> roots = new ArrayList<>();
        if (sec != null && sec.allowedWorkspaceRoots() != null) {
            for (String root : sec.allowedWorkspaceRoots()) {
                if (root != null && !root.isBlank()) {
                    roots.add(Path.of(root.trim()).toAbsolutePath().normalize());
                }
            }
        }
        return roots;
    }

    /** True when {@code workspace} (resolved through symlinks) is under an allowed root, or when no roots are configured. */
    public boolean isAllowedWorkspace(Path workspace) {
        List<Path> roots = allowedRoots();
        if (roots.isEmpty()) {
            return true;
        }
        Path real;
        try {
            real = workspace.toRealPath();
        } catch (IOException e) {
            real = workspace.toAbsolutePath().normalize();
        }
        for (Path root : roots) {
            Path rootReal;
            try {
                rootReal = root.toRealPath();
            } catch (IOException e) {
                rootReal = root;
            }
            if (real.startsWith(rootReal)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> clean(List<String> items) {
        List<String> out = new ArrayList<>();
        if (items != null) {
            for (String item : items) {
                if (item != null && !item.isBlank()) {
                    out.add(item.trim());
                }
            }
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private void loadOverrides() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) {
                return;
            }
            Map<String, ModuleSnapshot> modules = new LinkedHashMap<>(current.modules());
            if (root.get("modules") instanceof Map<?, ?> mods) {
                mods.forEach((name, node) -> {
                    if (node instanceof Map<?, ?> m) {
                        ModuleSnapshot base = modules.getOrDefault(String.valueOf(name), new ModuleSnapshot(ModuleMode.AUTO, String.valueOf(name), null, null, List.of(), List.of()));
                        modules.put(String.valueOf(name), new ModuleSnapshot(
                                m.get("mode") == null ? base.mode() : ModuleMode.valueOf(String.valueOf(m.get("mode")).toUpperCase()),
                                m.get("command") == null ? base.command() : String.valueOf(m.get("command")),
                                m.get("model") == null ? base.model() : String.valueOf(m.get("model")),
                                m.get("maxBudgetUsd") == null ? base.maxBudgetUsd() : Double.valueOf(String.valueOf(m.get("maxBudgetUsd"))),
                                m.get("allowedTools") instanceof List<?> l ? (List<String>) l : base.allowedTools(),
                                m.get("extraArgs") instanceof List<?> l2 ? (List<String>) l2 : base.extraArgs()));
                    }
                });
            }
            IsolationSnapshot iso = current.isolation();
            if (root.get("isolation") instanceof Map<?, ?> i) {
                iso = new IsolationSnapshot(
                        i.get("enabled") == null ? iso.enabled() : Boolean.parseBoolean(String.valueOf(i.get("enabled"))),
                        i.get("autoApply") == null ? iso.autoApply() : Boolean.parseBoolean(String.valueOf(i.get("autoApply"))),
                        i.get("keepWorktrees") == null ? iso.keepWorktrees() : Boolean.parseBoolean(String.valueOf(i.get("keepWorktrees"))),
                        i.get("maxPatchChars") == null ? iso.maxPatchChars() : Integer.parseInt(String.valueOf(i.get("maxPatchChars"))),
                        i.get("linkDirs") instanceof List<?> l ? (List<String>) l : iso.linkDirs(),
                        i.get("exclude") instanceof List<?> l2 ? (List<String>) l2 : iso.exclude());
            }
            Snapshot merged = new Snapshot(
                    root.get("workspace") == null ? current.workspace() : String.valueOf(root.get("workspace")),
                    root.get("concurrency") == null ? current.concurrency() : Integer.parseInt(String.valueOf(root.get("concurrency"))),
                    root.get("moduleTimeoutSeconds") == null ? current.moduleTimeoutSeconds() : Long.parseLong(String.valueOf(root.get("moduleTimeoutSeconds"))),
                    root.get("idleWarningSeconds") == null ? current.idleWarningSeconds() : Long.parseLong(String.valueOf(root.get("idleWarningSeconds"))),
                    modules, iso);
            current = validate(merged);
            log.info("Runtime settings loaded from {}", file);
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unreadable settings file {}: {}", file, e.toString());
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("workspace", current.workspace());
        root.put("concurrency", current.concurrency());
        root.put("moduleTimeoutSeconds", current.moduleTimeoutSeconds());
        root.put("idleWarningSeconds", current.idleWarningSeconds());
        Map<String, Object> modules = new LinkedHashMap<>();
        current.modules().forEach((name, m) -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("mode", m.mode().name());
            node.put("command", m.command());
            if (m.model() != null) {
                node.put("model", m.model());
            }
            if (m.maxBudgetUsd() != null) {
                node.put("maxBudgetUsd", m.maxBudgetUsd());
            }
            node.put("allowedTools", m.allowedTools());
            node.put("extraArgs", m.extraArgs());
            modules.put(name, node);
        });
        root.put("modules", modules);
        Map<String, Object> iso = new LinkedHashMap<>();
        iso.put("enabled", current.isolation().enabled());
        iso.put("autoApply", current.isolation().autoApply());
        iso.put("keepWorktrees", current.isolation().keepWorktrees());
        iso.put("maxPatchChars", current.isolation().maxPatchChars());
        iso.put("linkDirs", current.isolation().linkDirs());
        iso.put("exclude", current.isolation().exclude());
        root.put("isolation", iso);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new Yaml(options).dump(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("설정 파일을 쓸 수 없습니다: " + file, e);
        }
    }
}
