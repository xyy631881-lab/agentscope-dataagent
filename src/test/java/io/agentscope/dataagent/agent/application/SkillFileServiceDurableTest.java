/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.dataagent.agent.api.AgentSkillsController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SkillFileServiceDurableTest {

    @TempDir Path workspace;

    @Test
    void installsAndOverwritesACompleteVersionedBundle() throws Exception {
        String markdown =
                "---\nname: SQL Analysis\ndescription: Reusable SQL workflow\n---\n# SQL Analysis\n";
        AgentSkillsController.SkillMarketplaceMeta v1 =
                new AgentSkillsController.SkillMarketplaceMeta(
                        "local", "team-shared", "sql-analysis", "2026-07-21T00:00:00Z", 1);

        AgentSkillsController.WorkspaceSkillInfo installed =
                SkillFileService.installDurable(
                        workspace,
                        "sql-analysis",
                        markdown,
                        Map.of("templates/query.sql", "select 1;"),
                        v1,
                        false);

        assertEquals("SQL Analysis", installed.name());
        assertEquals("Reusable SQL workflow", installed.description());
        assertEquals(1, installed.resourceCount());
        assertEquals(1, installed.marketplace().version());
        assertEquals(
                "select 1;",
                Files.readString(
                        workspace.resolve("skills/sql-analysis/templates/query.sql"),
                        StandardCharsets.UTF_8));

        ResponseStatusException conflict =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                SkillFileService.installDurable(
                                        workspace,
                                        "sql-analysis",
                                        markdown,
                                        Map.of(),
                                        v1,
                                        false));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        AgentSkillsController.SkillMarketplaceMeta v2 =
                new AgentSkillsController.SkillMarketplaceMeta(
                        "local", "team-shared", "sql-analysis", "2026-07-21T00:01:00Z", 2);
        AgentSkillsController.WorkspaceSkillInfo overwritten =
                SkillFileService.installDurable(
                        workspace,
                        "sql-analysis",
                        markdown.replace("Reusable SQL workflow", "Reviewed SQL workflow"),
                        Map.of("scripts/check.sql", "select 2;"),
                        v2,
                        true);

        assertEquals("Reviewed SQL workflow", overwritten.description());
        assertEquals(2, overwritten.marketplace().version());
        assertTrue(overwritten.hasScripts());
        assertFalse(Files.exists(workspace.resolve("skills/sql-analysis/templates/query.sql")));
        assertEquals(1, SkillFileService.listDurable(workspace).size());
        AgentSkillsController.WorkspaceSkillDetail detail =
                SkillFileService.readDurableDetail(workspace, "sql-analysis");
        assertEquals("select 2;", detail.resources().get("scripts/check.sql"));
        try (var children = Files.list(workspace.resolve("skills"))) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".install-")));
        }

        SkillFileService.deleteDurable(workspace, "sql-analysis");
        assertTrue(SkillFileService.listDurable(workspace).isEmpty());
    }
}
