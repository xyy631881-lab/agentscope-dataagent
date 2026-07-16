package io.agentscope.dataagent.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

class AgentWorkspaceControllerRouteTest {

    @Test
    void exposesTheWorkspaceApiBasePath() {
        RequestMapping mapping = AgentWorkspaceController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/api/agents/{agentId}/workspace");
    }
}
