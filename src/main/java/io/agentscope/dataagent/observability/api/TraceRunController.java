package io.agentscope.dataagent.observability.api;

import io.agentscope.dataagent.observability.application.TraceRunService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Separate runtime-records feed. Detailed message/tool traces remain on the chat transcript. */
@RestController
@RequestMapping("/api/traces")
public class TraceRunController {
    private final TraceRunService traceRuns;

    public TraceRunController(TraceRunService traceRuns) { this.traceRuns = traceRuns; }

    @GetMapping("/me")
    public List<TraceRunService.RunView> mine(
            @RequestParam(defaultValue = "40") int limit, Authentication authentication) {
        return traceRuns.recentForUser(userId(authentication), limit);
    }

    private static String userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof String userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return userId;
    }
}
