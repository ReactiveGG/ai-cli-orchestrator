package dev.orchestrator.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.orchestrator.server.api.CatalogController;
import dev.orchestrator.server.job.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "orchestrator.data-dir=${java.io.tmpdir}/ai-orchestrator-test-${random.uuid}",
        "orchestrator.modules.claude.mode=STUB",
        "orchestrator.modules.codex.mode=STUB",
        "orchestrator.status.anthropic-status-url=",
        "orchestrator.security.require-token=true"
})
class ServerApplicationTest {
    @Autowired
    CatalogController catalog;

    @Autowired
    DashboardService dashboard;

    @org.springframework.beans.factory.annotation.Autowired
    dev.orchestrator.server.security.ApiToken apiToken;

    @Test
    void generatesAnApiTokenPerInstall() {
        assertTrue(apiToken.value().length() >= 32);
    }

    @Test
    void contextLoadsAndCatalogListsCommands() {
        assertEquals(3, catalog.catalog().flows().size());
        assertEquals("1-1-2-1", catalog.catalog().flows().get(1).signature());
        assertTrue(catalog.catalog().roles().stream().anyMatch(role -> role.name().equals("planner")));
        assertEquals(2, dashboard.build().status().modules().size());
    }
}
