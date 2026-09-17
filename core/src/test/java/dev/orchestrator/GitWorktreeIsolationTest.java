package dev.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.isolation.Candidate;
import dev.orchestrator.isolation.CandidateDecision;
import dev.orchestrator.isolation.CandidatePatch;
import dev.orchestrator.isolation.GitWorktreeIsolation;
import dev.orchestrator.isolation.IsolationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class GitWorktreeIsolationTest {
    @TempDir
    Path tempDir;

    private Path workspace;
    private Path worktrees;
    private Path patches;
    private GitWorktreeIsolation isolation;

    private static String git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        }
        return out;
    }

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(tempDir.resolve("repo"));
        worktrees = tempDir.resolve("wt");
        patches = tempDir.resolve("patches");
        git(workspace, "init", "-q");
        Files.writeString(workspace.resolve("app.py"), "print('v1')\n");
        Files.writeString(workspace.resolve(".gitignore"), "node_modules/\n__pycache__/\n");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-q", "-m", "init");
        // uncommitted work: a tracked edit, an untracked file, and an ignored dir
        Files.writeString(workspace.resolve("app.py"), "print('v1 edited')\n");
        Files.writeString(workspace.resolve("notes.md"), "untracked\n");
        Files.createDirectories(workspace.resolve("node_modules/dep"));
        Files.writeString(workspace.resolve("node_modules/dep/index.js"), "module.exports = 1\n");
        isolation = new GitWorktreeIsolation(worktrees, List.of("node_modules"));
    }

    @Test
    void candidatesStartFromWorkingTreeAndLeaveUserStateUntouched() throws Exception {
        String statusBefore = git(workspace, "status", "--porcelain");
        String headBefore = git(workspace, "rev-parse", "HEAD");

        List<Candidate> candidates = isolation.prepare("job1", workspace, 2);

        assertEquals(2, candidates.size());
        for (Candidate c : candidates) {
            assertEquals("print('v1 edited')\n", Files.readString(c.workingDir().resolve("app.py")), "tracked edit included");
            assertEquals("untracked\n", Files.readString(c.workingDir().resolve("notes.md")), "untracked file included");
            assertTrue(Files.isSymbolicLink(c.workingDir().resolve("node_modules")), "ignored dir linked");
            assertEquals(candidates.get(0).baseCommit(), c.baseCommit());
        }
        assertEquals(statusBefore, git(workspace, "status", "--porcelain"), "user index/working tree unchanged");
        assertEquals(headBefore, git(workspace, "rev-parse", "HEAD"), "branch not moved");
        assertTrue(git(workspace, "worktree", "list").lines().count() == 3);
    }

    @Test
    void captureApplyAndCleanupRoundTrip() throws Exception {
        List<Candidate> candidates = isolation.prepare("job2", workspace, 2);
        Candidate c1 = candidates.get(0);
        Candidate c2 = candidates.get(1);
        // coder 1 edits an existing file; coder 2 adds a new file and a pycache (ignored)
        Files.writeString(c1.workingDir().resolve("app.py"), "print('candidate 1')\n");
        Files.writeString(c2.workingDir().resolve("feature.py"), "def f():\n    return 2\n");
        Files.createDirectories(c2.workingDir().resolve("__pycache__"));
        Files.writeString(c2.workingDir().resolve("__pycache__/x.pyc"), "junk");

        CandidatePatch p1 = isolation.capture(c1, patches);
        CandidatePatch p2 = isolation.capture(c2, patches);

        assertFalse(p1.isEmpty());
        assertEquals(1, p1.filesChanged());
        assertEquals(1, p1.insertions());
        assertEquals(1, p1.deletions());
        assertTrue(p1.patch().contains("-print('v1 edited')") && p1.patch().contains("+print('candidate 1')"));
        assertEquals(1, p2.filesChanged());
        assertTrue(p2.patch().contains("feature.py"));
        assertFalse(p2.patch().contains("__pycache__"), "ignored files never enter a candidate");
        assertFalse(p2.patch().contains("node_modules"), "linked dirs never enter a candidate");
        assertTrue(Files.isRegularFile(patches.resolve("c2.patch")));
        assertNotEquals(p1.commit(), p1.baseCommit());

        isolation.apply(p2, workspace);

        assertEquals("def f():\n    return 2\n", Files.readString(workspace.resolve("feature.py")));
        assertEquals("print('v1 edited')\n", Files.readString(workspace.resolve("app.py")), "other candidate not applied");
        assertTrue(git(workspace, "status", "--porcelain").contains("?? feature.py"), "applied to working tree, not committed");

        isolation.cleanup("job2", workspace);

        assertFalse(Files.exists(worktrees.resolve("job2")));
        assertEquals(1, git(workspace, "worktree", "list").lines().count());
        assertTrue(Files.isRegularFile(patches.resolve("c1.patch")), "patch files survive cleanup");
    }

    @Test
    void emptyCandidateAndConflictingApply() throws Exception {
        List<Candidate> candidates = isolation.prepare("job3", workspace, 1);
        CandidatePatch untouched = isolation.capture(candidates.get(0), patches);
        assertTrue(untouched.isEmpty());
        assertEquals("변경 없음", untouched.summary());
        assertEquals(untouched.baseCommit(), untouched.commit());
        isolation.apply(untouched, workspace);   // no-op

        Files.writeString(candidates.get(0).workingDir().resolve("app.py"), "print('candidate')\n");
        CandidatePatch patch = isolation.capture(candidates.get(0), patches);
        Files.writeString(workspace.resolve("app.py"), "print('user changed it meanwhile')\n");

        IsolationException error = assertThrows(IsolationException.class, () -> isolation.apply(patch, workspace));
        assertTrue(error.getMessage().contains("후보 1 적용 실패"), error.getMessage());
        isolation.cleanup("job3", workspace);
    }

    @Test
    void worksInEmptyRepoAndRejectsNonRepo() throws Exception {
        Path empty = Files.createDirectories(tempDir.resolve("empty"));
        git(empty, "init", "-q");
        Files.writeString(empty.resolve("a.txt"), "a\n");
        List<Candidate> candidates = isolation.prepare("job4", empty, 1);
        assertEquals("a\n", Files.readString(candidates.get(0).workingDir().resolve("a.txt")));
        isolation.cleanup("job4", empty);

        Path plain = Files.createDirectories(tempDir.resolve("plain"));
        assertFalse(isolation.supports(plain));
        assertThrows(IsolationException.class, () -> isolation.prepare("job5", plain, 2));
    }

    @Test
    void subdirectoryWorkspaceMapsToSameSubdirInWorktree() throws Exception {
        Path sub = Files.createDirectories(workspace.resolve("service"));
        Files.writeString(sub.resolve("s.py"), "s\n");
        List<Candidate> candidates = isolation.prepare("job6", sub, 1);
        Candidate c = candidates.get(0);
        assertTrue(c.workingDir().endsWith("service"));
        assertEquals("s\n", Files.readString(c.workingDir().resolve("s.py")));
        Files.writeString(c.workingDir().resolve("s.py"), "changed\n");
        CandidatePatch patch = isolation.capture(c, patches);
        assertTrue(patch.patch().contains("service/s.py"));
        isolation.apply(patch, sub);
        assertEquals("changed\n", Files.readString(sub.resolve("s.py")));
        isolation.cleanup("job6", sub);
    }

    @Test
    void decisionParsing() {
        assertEquals(Optional.of(2), CandidateDecision.parse("...\n채택: 후보 2\n"));
        assertEquals(Optional.of(3), CandidateDecision.parse("채택 후보: 3"));
        assertEquals(Optional.of(1), CandidateDecision.parse("후보 1과 2를 비교했다. 채택: 후보 2 ... 정정한다. 채택: 후보 1"));
        assertEquals(Optional.of(2), CandidateDecision.parse("ADOPT: candidate 2"));
        assertEquals(Optional.of(2), CandidateDecision.parse("Adopted: #2"));
        assertEquals(Optional.empty(), CandidateDecision.parse("아무 결정도 없다"));
        assertEquals(Optional.empty(), CandidateDecision.parse("마지막 줄에 `채택: 후보 N` 형식으로 쓴다. 없으면 `채택: 후보 0`"), "quoted instruction is not a decision");
        assertEquals(Optional.of(3), CandidateDecision.parse("지시: `채택: 후보 N`을 쓴다.\n채택: 후보 3"));
        assertEquals(Optional.empty(), CandidateDecision.parse(null));
    }
}
