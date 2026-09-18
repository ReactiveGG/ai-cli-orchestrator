package dev.orchestrator.server.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClaudeStatusServiceTest {
    @Test
    void parsesAuthStatusJson() {
        ClaudeStatusService.AuthStatus auth = ClaudeStatusService.parseAuth("""
                {
                  "loggedIn": true,
                  "authMethod": "claude.ai",
                  "apiProvider": "firstParty"
                }
                """);
        assertTrue(auth.loggedIn());
        assertEquals("claude.ai", auth.authMethod());

        ClaudeStatusService.AuthStatus out = ClaudeStatusService.parseAuth("warning: something\n{\"loggedIn\": false}");
        assertEquals(false, out.loggedIn());
        assertNull(out.authMethod());
    }

    @Test
    void ignoresOutputThatIsNotAuthJson() {
        assertNull(ClaudeStatusService.parseAuth("Unknown command: auth"));
        assertNull(ClaudeStatusService.parseAuth("{\"version\": \"2.1.274\"}"));
        assertNull(ClaudeStatusService.parseAuth(null));
    }
}
