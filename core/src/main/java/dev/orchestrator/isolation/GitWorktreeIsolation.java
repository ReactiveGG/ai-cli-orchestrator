package dev.orchestrator.isolation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Candidate isolation with git worktrees.
 *
 * <p>A <em>base commit</em> is built from the workspace's HEAD plus its working
 * tree (tracked and untracked, .gitignore respected) through a temporary index,
 * so the user's index and branch are never touched. Each candidate is a detached
 * worktree at that commit under {@code <root>/<jobId>/c<k>}. Ignored directories
 * listed in {@code linkDirs} (node_modules, .venv, ...) are symlinked from the
 * workspace so tools and tests still run inside the worktree.
 */
public final class GitWorktreeIsolation implements WorkspaceIsolation {
    private static final Pattern SHORTSTAT = Pattern.compile("(\\d+) files? changed(?:, (\\d+) insertions?\\(\\+\\))?(?:, (\\d+) deletions?\\(-\\))?");

    /** Junk that never belongs in a candidate even when the repo forgot to ignore it. */
    public static final List<String> DEFAULT_EXCLUDES = List.of("__pycache__", "*.pyc", ".DS_Store", "Thumbs.db", "*.swp");

    private final Path root;
    private final List<String> linkDirs;
    private final List<String> excludes;
    private final String gitCommand;

    public GitWorktreeIsolation(Path root, List<String> linkDirs) {
        this(root, linkDirs, DEFAULT_EXCLUDES, "git");
    }

    public GitWorktreeIsolation(Path root, List<String> linkDirs, List<String> excludes) {
        this(root, linkDirs, excludes, "git");
    }

    public GitWorktreeIsolation(Path root, List<String> linkDirs, List<String> excludes, String gitCommand) {
        this.root = root;
        this.linkDirs = linkDirs == null ? List.of() : List.copyOf(linkDirs);
        this.excludes = excludes == null ? List.of() : List.copyOf(excludes);
        this.gitCommand = gitCommand;
    }

    public Path root() {
        return root;
    }

    @Override
    public boolean supports(Path workspace) {
        try {
            return workspace != null && Files.isDirectory(workspace) && git(workspace, "rev-parse", "--is-inside-work-tree").trim().equals("true");
        } catch (IsolationException e) {
            return false;
        }
    }

    @Override
    public List<Candidate> prepare(String jobId, Path workspace, int count) {
        if (!supports(workspace)) {
            throw new IsolationException("병렬 코더는 git 저장소에서만 쓸 수 있습니다: " + workspace
                    + " (git init 하거나 코더를 1개로 줄이세요)");
        }
        Path toplevel = Path.of(git(workspace, "rev-parse", "--show-toplevel").trim()).toAbsolutePath().normalize();
        Path relative = toplevel.relativize(workspace.toAbsolutePath().normalize());
        Path jobRoot = root.resolve(jobId);
        try {
            Files.createDirectories(jobRoot);
        } catch (IOException e) {
            throw new IsolationException("worktree 디렉터리를 만들 수 없습니다: " + jobRoot, e);
        }
        String base = createBaseCommit(toplevel, jobRoot, jobId);
        List<Candidate> candidates = new ArrayList<>();
        try {
            for (int k = 1; k <= count; k++) {
                Path worktree = jobRoot.resolve("c" + k);
                git(toplevel, "worktree", "add", "--detach", worktree.toString(), base);
                linkIgnoredDirs(toplevel, worktree);
                candidates.add(new Candidate(k, worktree, worktree.resolve(relative).normalize(), base));
            }
        } catch (RuntimeException e) {
            cleanup(jobId, workspace);
            throw e;
        }
        return candidates;
    }

    /** HEAD + working tree as a commit object, built through a private index. */
    private String createBaseCommit(Path toplevel, Path jobRoot, String jobId) {
        Path index = jobRoot.resolve("base.index");
        Map<String, String> env = Map.of("GIT_INDEX_FILE", index.toAbsolutePath().toString());
        boolean hasHead = exec(toplevel, Map.of(), false, "rev-parse", "--verify", "-q", "HEAD").exit == 0;
        if (hasHead) {
            git(toplevel, env, "read-tree", "HEAD");
        } else {
            git(toplevel, env, "read-tree", "--empty");
        }
        git(toplevel, env, "add", "-A", "--", ".");
        String tree = git(toplevel, env, "write-tree").trim();
        String message = "orchestrator base " + jobId;
        String commit = hasHead
                ? git(toplevel, env, "commit-tree", tree, "-p", "HEAD", "-m", message).trim()
                : git(toplevel, env, "commit-tree", tree, "-m", message).trim();
        try {
            Files.deleteIfExists(index);
        } catch (IOException ignored) {
            // best effort
        }
        return commit;
    }

    private void linkIgnoredDirs(Path toplevel, Path worktree) {
        for (String name : linkDirs) {
            Path source = toplevel.resolve(name);
            Path target = worktree.resolve(name);
            if (!Files.isDirectory(source) || Files.exists(target)) {
                continue;
            }
            boolean ignored = exec(toplevel, Map.of(), false, "check-ignore", "-q", name).exit == 0;
            if (!ignored) {
                continue;   // a tracked/untracked dir is part of the base commit already
            }
            try {
                Files.createSymbolicLink(target, source.toAbsolutePath());
            } catch (IOException | UnsupportedOperationException e) {
                // Symlinks need Developer Mode / admin on Windows; a directory junction does not.
                if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                    try {
                        Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", target.toString(), source.toAbsolutePath().toString())
                                .redirectErrorStream(true).start();
                        p.getInputStream().readAllBytes();
                        p.waitFor(30, TimeUnit.SECONDS);
                    } catch (IOException | InterruptedException junctionError) {
                        // the worktree simply lacks the directory then
                    }
                }
            }
        }
    }

    @Override
    public CandidatePatch capture(Candidate candidate, Path patchDir) {
        Path wt = candidate.worktree();
        // Linked ignored dirs are symlinks in the worktree; a `dir/` ignore rule does not
        // cover a symlink, so exclude them explicitly from the candidate.
        List<String> addArgs = new ArrayList<>(List.of("add", "-A", "--", "."));
        for (String name : linkDirs) {
            Path linked = wt.resolve(name);
            if (Files.isSymbolicLink(linked) || isJunction(linked)) {
                addArgs.add(":(exclude)" + name);
            }
        }
        for (String pattern : excludes) {
            addArgs.add(":(exclude,glob)**/" + pattern);
            addArgs.add(":(exclude,glob)" + pattern);
        }
        git(wt, addArgs.toArray(String[]::new));
        boolean changed = exec(wt, Map.of(), false, "diff", "--cached", "--quiet").exit != 0;
        String commit = candidate.baseCommit();
        if (changed) {
            git(wt, Map.of("GIT_AUTHOR_NAME", "orchestrator", "GIT_AUTHOR_EMAIL", "orchestrator@local",
                            "GIT_COMMITTER_NAME", "orchestrator", "GIT_COMMITTER_EMAIL", "orchestrator@local"),
                    "commit", "-q", "-m", "candidate " + candidate.index());
            commit = git(wt, "rev-parse", "HEAD").trim();
        }
        String stat = changed ? git(wt, "diff", "--stat", candidate.baseCommit(), commit) : "";
        String shortstat = changed ? git(wt, "diff", "--shortstat", candidate.baseCommit(), commit) : "";
        String patch = changed ? git(wt, "diff", "--binary", candidate.baseCommit(), commit) : "";
        int[] numbers = parseShortstat(shortstat);
        Path patchFile = patchDir.resolve("c" + candidate.index() + ".patch");
        try {
            Files.createDirectories(patchDir);
            Files.writeString(patchFile, patch, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IsolationException("patch 파일을 쓸 수 없습니다: " + patchFile, e);
        }
        return new CandidatePatch(candidate.index(), candidate.baseCommit(), commit, stat.stripTrailing(), patch, patchFile,
                numbers[0], numbers[1], numbers[2]);
    }

    @Override
    public void apply(CandidatePatch patch, Path workspace) {
        if (patch.isEmpty()) {
            return;
        }
        Path toplevel = Path.of(git(workspace, "rev-parse", "--show-toplevel").trim());
        String file = patch.patchFile().toAbsolutePath().toString();
        // Plain apply works against the working tree (also for files the user never added);
        // --3way needs the preimage in the index, so it is only a fallback for tracked conflicts.
        Result direct = exec(toplevel, Map.of(), false, "apply", "--whitespace=nowarn", file);
        if (direct.exit == 0) {
            return;
        }
        Result threeWay = exec(toplevel, Map.of(), false, "apply", "--3way", "--whitespace=nowarn", file);
        if (threeWay.exit != 0) {
            throw new IsolationException("후보 " + patch.index() + " 적용 실패: " + direct.output.strip()
                    + " (patch: " + patch.patchFile() + ")");
        }
    }

    @Override
    public void revert(CandidatePatch patch, Path workspace) {
        if (patch.isEmpty()) {
            return;
        }
        Path toplevel = Path.of(git(workspace, "rev-parse", "--show-toplevel").trim());
        String file = patch.patchFile().toAbsolutePath().toString();
        // Reverse apply: files the patch created are removed, edits are undone. Fails if the user changed
        // those files since, which is the right outcome — we must not silently discard their work.
        Result reverse = exec(toplevel, Map.of(), false, "apply", "-R", "--whitespace=nowarn", file);
        if (reverse.exit != 0) {
            throw new IsolationException("후보 " + patch.index() + " 되돌리기 실패 (적용 후 작업 공간이 바뀐 듯합니다): " + reverse.output.strip()
                    + " (patch: " + patch.patchFile() + ")");
        }
    }

    @Override
    public void cleanup(String jobId, Path workspace) {
        Path jobRoot = root.resolve(jobId);
        if (!Files.isDirectory(jobRoot)) {
            return;
        }
        Path toplevel = null;
        try {
            toplevel = Path.of(git(workspace, "rev-parse", "--show-toplevel").trim());
        } catch (IsolationException ignored) {
            // workspace gone; just delete the directories
        }
        try (Stream<Path> dirs = Files.list(jobRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (toplevel != null) {
                    exec(toplevel, Map.of(), false, "worktree", "remove", "--force", dir.toAbsolutePath().toString());
                }
                deleteRecursively(dir);
            }
        } catch (IOException e) {
            throw new IsolationException("worktree 정리 실패: " + jobRoot, e);
        }
        if (toplevel != null) {
            exec(toplevel, Map.of(), false, "worktree", "prune");
        }
        deleteRecursively(jobRoot);
    }

    /** A Windows directory junction (reparse point): Java reports it as "other", not as a symlink. */
    private static boolean isJunction(Path path) {
        try {
            return Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (IOException e) {
            return false;
        }
    }

    // ---- helpers -----------------------------------------------------------

    private record Result(int exit, String output) {
    }

    private String git(Path cwd, String... args) {
        return git(cwd, Map.of(), args);
    }

    private String git(Path cwd, Map<String, String> env, String... args) {
        Result result = exec(cwd, env, true, args);
        return result.output;
    }

    private Result exec(Path cwd, Map<String, String> env, boolean failOnError, String... args) {
        List<String> command = new ArrayList<>();
        command.add(gitCommand);
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        // The base commit is internal (temp index, never pushed); do not require the user to have
        // configured a git identity — a fresh Windows PC has none and commit-tree fails with "Author identity unknown".
        builder.environment().putIfAbsent("GIT_AUTHOR_NAME", "AI CLI Orchestrator");
        builder.environment().putIfAbsent("GIT_AUTHOR_EMAIL", "orchestrator@localhost");
        builder.environment().putIfAbsent("GIT_COMMITTER_NAME", "AI CLI Orchestrator");
        builder.environment().putIfAbsent("GIT_COMMITTER_EMAIL", "orchestrator@localhost");
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IsolationException("git " + String.join(" ", args) + " 시간 초과");
            }
            if (failOnError && process.exitValue() != 0) {
                throw new IsolationException("git " + String.join(" ", args) + " 실패 (exit " + process.exitValue() + "): " + output.strip());
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            throw new IsolationException("git 실행 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IsolationException("git 실행이 중단됨", e);
        }
    }

    private static int[] parseShortstat(String shortstat) {
        Matcher m = SHORTSTAT.matcher(shortstat == null ? "" : shortstat);
        if (!m.find()) {
            return new int[] {0, 0, 0};
        }
        return new int[] {
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))
        };
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
