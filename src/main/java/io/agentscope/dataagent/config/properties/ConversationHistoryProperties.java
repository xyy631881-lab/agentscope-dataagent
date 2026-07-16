package io.agentscope.dataagent.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default retention policy for one user's conversations with one agent. */
@ConfigurationProperties(prefix = "dataagent.conversation.history")
public class ConversationHistoryProperties {

    private int defaultMaxSessions = 100;

    public int getDefaultMaxSessions() {
        return defaultMaxSessions;
    }

    public void setDefaultMaxSessions(int defaultMaxSessions) {
        this.defaultMaxSessions = defaultMaxSessions;
    }
}
