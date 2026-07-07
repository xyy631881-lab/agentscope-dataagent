package io.agentscope.dataagent.conversation.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
/**
 * Pure, stateless SSE/key helpers backing {@link ChatController}.
 * Extracted so the controller reads as request handling, not JSON plumbing.
 * No instance state, no framework coupling.
*/
final class ChatSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    public static String normalizedConversationId(String key) {
        return (key != null && !key.isBlank()) ? key.trim() : null;
    }


    public static String toJson(String eventType, Object data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"" + eventType + "\"}";
        }
    }

}
