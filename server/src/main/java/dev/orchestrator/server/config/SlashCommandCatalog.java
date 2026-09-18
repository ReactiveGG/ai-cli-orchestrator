package dev.orchestrator.server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Slash commands an agent chip can be given, with what each does, so the user
 * picks from a list instead of remembering names: the orchestrator's built-ins
 * ({@code /plan}, {@code /resume}, {@code /continue}) plus what Claude Code would
 * find in the workspace and the user's home: {@code .claude/commands/**.md}
 * (project commands) and {@code .claude/skills/<name>/SKILL.md} (skills).
 */
@Component
public class SlashCommandCatalog {
    /**
     * @param name     the command as typed, e.g. {@code /review}
     * @param kind     {@code builtin}, {@code project}, {@code user} or {@code skill}
     * @param argument what to put after the name, null when it takes none
     * @param scope    which roles it makes sense for (empty = any)
     */
    public record SlashCommand(String name, String kind, String description, String argument, List<String> scope, String source) {
    }

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final int MAX_FILES = 300;

    private final RuntimeSettings settings;

    public SlashCommandCatalog(RuntimeSettings settings) {
        this.settings = settings;
    }

    public List<SlashCommand> list() {
        List<SlashCommand> out = new ArrayList<>(builtIns());
        Path root = settings.workspace();
        if (root != null) {
            out.addAll(commandsUnder(root.resolve(".claude").resolve("commands"), "project"));
            out.addAll(skillsUnder(root.resolve(".claude").resolve("skills"), "skill"));
        }
        Path home = Path.of(System.getProperty("user.home", ""));
        out.addAll(commandsUnder(home.resolve(".claude").resolve("commands"), "user"));
        out.addAll(skillsUnder(home.resolve(".claude").resolve("skills"), "skill"));
        return out;
    }

    static List<SlashCommand> builtIns() {
        return List.of(
                new SlashCommand("/plan", "builtin",
                        "계획 전용 권한 모드(--permission-mode plan). 파일을 읽고 계획만 세운다. 코더에 주면 1/2 계획(plan 모드) → 2/2 같은 세션을 이어받아 acceptEdits로 구현, 두 번 실행한다",
                        null, List.of(), "orchestrator"),
                new SlashCommand("/resume", "builtin",
                        "그 세션을 이어받아 실행(--resume <세션 id>). 세션 id는 요약 로그의 'claude … · 세션 <id>' 줄에 남는다. id 없이 쓰면 /continue와 같다",
                        "<세션 id>", List.of(), "orchestrator"),
                new SlashCommand("/continue", "builtin",
                        "이 작업 공간에서 가장 최근에 돌던 Claude 세션을 이어받아 실행(--continue)",
                        null, List.of(), "orchestrator"));
    }

    /** {@code .claude/commands/<name>.md} or nested {@code <dir>/<name>.md} → {@code /name} or {@code /dir:name}. */
    static List<SlashCommand> commandsUnder(Path dir, String kind) {
        List<SlashCommand> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir, 4)) {
            files.filter(p -> p.toString().endsWith(".md")).sorted().limit(MAX_FILES).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                String name = "/" + rel.substring(0, rel.length() - 3).replace('/', ':');
                Map<String, String> fm = frontmatter(readHead(p));
                String description = fm.getOrDefault("description", firstLine(readHead(p)));
                String argument = fm.containsKey("argument-hint") ? fm.get("argument-hint") : (readHead(p).contains("$ARGUMENTS") || readHead(p).contains("$1") ? "<인자>" : null);
                out.add(new SlashCommand(name, kind, description, argument, List.of(), p.toString()));
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            // unreadable tree: skip
        }
        return out;
    }

    /** {@code .claude/skills/<name>/SKILL.md} → {@code /name} (Claude Code exposes user-invocable skills as slash commands). */
    static List<SlashCommand> skillsUnder(Path dir, String kind) {
        List<SlashCommand> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> dirs = Files.list(dir)) {
            dirs.filter(Files::isDirectory).sorted().limit(MAX_FILES).forEach(d -> {
                Path skill = d.resolve("SKILL.md");
                if (!Files.isRegularFile(skill)) {
                    return;
                }
                String head = readHead(skill);
                Map<String, String> fm = frontmatter(head);
                String name = "/" + fm.getOrDefault("name", d.getFileName().toString());
                out.add(new SlashCommand(name, kind, fm.getOrDefault("description", firstLine(head)), fm.get("argument-hint"), List.of(), skill.toString()));
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            // skip
        }
        return out;
    }

    static String readHead(Path p) {
        try {
            String s = Files.readString(p);
            return s.length() > 4000 ? s.substring(0, 4000) : s;
        } catch (IOException | java.io.UncheckedIOException e) {
            return "";
        }
    }

    /** Minimal YAML front matter reader: {@code key: value} lines between the first two {@code ---}. */
    static Map<String, String> frontmatter(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = FRONTMATTER.matcher(text);
        if (!m.find()) {
            return out;
        }
        for (String line : m.group(1).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String value = line.substring(colon + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(line.substring(0, colon).strip(), value);
            }
        }
        return out;
    }

    static String firstLine(String text) {
        String body = FRONTMATTER.matcher(text).replaceFirst("").strip();
        int nl = body.indexOf('\n');
        String line = (nl < 0 ? body : body.substring(0, nl)).strip().replaceFirst("^#+\\s*", "");
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }
}
