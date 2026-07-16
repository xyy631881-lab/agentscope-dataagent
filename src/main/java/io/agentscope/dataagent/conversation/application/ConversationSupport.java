package io.agentscope.dataagent.conversation.application;

import io.agentscope.dataagent.conversation.domain.SessionEntry;
import io.agentscope.dataagent.conversation.domain.SessionKind;
import io.agentscope.dataagent.conversation.domain.SessionMaintenanceConfig;
import io.agentscope.dataagent.runtime.config.AgentscopeConfig;

/**
 * Pure, stateless helpers backing {@link ConversationService}: gateKey parsing,
 * session/agent matching, and maintenance-config resolution.
 *
 * <p>Extracted so the service reads as orchestration, not string-parsing boilerplate.
 * No instance state, no framework coupling.
 */
final class ConversationSupport {

    /**
     * The logical agent id is persisted with every new session and stays valid across process
     * restarts.  The gateway id is only a compatibility fallback for sessions created before
     * that column was populated.
     */
    static boolean sessionMatchesAgent(
            SessionEntry e, String requestedAgentId, String gatewayAgentId) {
        if (requestedAgentId != null
                && !requestedAgentId.isBlank()
                && requestedAgentId.equals(e.agentId())) {
            return true;
        }
        if (gatewayAgentId == null) return false;
        String gateKey = e.gateKey();
        if (e.kind() == SessionKind.MAIN) {
            return gateKey != null && extractGatewayAgentId(gateKey).equals(gatewayAgentId);
        }
        return gateKey == null || extractGatewayAgentId(gateKey).equals(gatewayAgentId);
    }
    static String extractGatewayAgentId(String gateKey) {
        String needle = "|x:agentId=";
        int i = gateKey.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        return end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
    }
    static String extractConversationId(String gateKey) {
        if (gateKey == null) return null;
        String needle = "|t:";
        int i = gateKey.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        String val = end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
        return val.isEmpty() ? null : val;
    }
    public static SessionMaintenanceConfig resolveMaintenanceConfig(AgentscopeConfig fileConfig) {
        var sessionCfg = fileConfig != null ? fileConfig.getSession() : null;
        if (sessionCfg == null || sessionCfg.getMaintenance() == null) {
            return SessionMaintenanceConfig.disabled();
        }
        var m = sessionCfg.getMaintenance();
        String mode = m.getMode();
        if (mode == null || mode.isBlank() || "off".equalsIgnoreCase(mode)) {
            return SessionMaintenanceConfig.disabled();
        }
        long pruneAfterMs = m.pruneAfterMs();
        int maxEntries = m.getMaxEntries() != null ? m.getMaxEntries() : 0;
        return SessionMaintenanceConfig.enabled(pruneAfterMs, maxEntries);
    }

}
