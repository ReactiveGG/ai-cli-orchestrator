package dev.orchestrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlashCommandCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void listsProjectCommandsAndSkillsWithDescriptions() throws Exception {
        Path commands = Files.createDirectories(tempDir.resolve(".claude/commands/git"));
        Files.writeString(tempDir.resolve(".claude/commands/review.md"), "---\ndescription: 리뷰 체크리스트로 검토\nargument-hint: <경로>\n---\nReview $ARGUMENTS\n");
        Files.writeString(commands.resolve("commit.md"), "# 커밋 메시지 작성\n\n변경을 커밋한다.\n");
        Path skill = Files.createDirectories(tempDir.resolve(".claude/skills/spec"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: spec\ndescription: \"요구사항을 스펙으로\"\n---\n...");

        List<SlashCommandCatalog.SlashCommand> cmds = SlashCommandCatalog.commandsUnder(tempDir.resolve(".claude/commands"), "project");
        assertEquals(List.of("/git:commit", "/review"), cmds.stream().map(SlashCommandCatalog.SlashCommand::name).toList());
        SlashCommandCatalog.SlashCommand review = cmds.get(1);
        assertEquals("리뷰 체크리스트로 검토", review.description());
        assertEquals("<경로>", review.argument());
        assertEquals("커밋 메시지 작성", cmds.get(0).description(), "no front matter → first heading/line");
        assertNull(cmds.get(0).argument());

        List<SlashCommandCatalog.SlashCommand> skills = SlashCommandCatalog.skillsUnder(tempDir.resolve(".claude/skills"), "skill");
        assertEquals("/spec", skills.get(0).name());
        assertEquals("요구사항을 스펙으로", skills.get(0).description());

        assertTrue(SlashCommandCatalog.builtIns().stream().map(SlashCommandCatalog.SlashCommand::name).toList().containsAll(List.of("/plan", "/resume", "/continue")));
        assertTrue(SlashCommandCatalog.commandsUnder(tempDir.resolve("missing"), "project").isEmpty());
    }
}
