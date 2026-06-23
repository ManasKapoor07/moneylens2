package com.moneylens.controller;

import com.moneylens.dto.CopilotMessageDto;
import com.moneylens.service.AiCopilotService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/copilot")
public class CopilotController {

    private final AiCopilotService copilotService;

    public CopilotController(AiCopilotService copilotService) {
        this.copilotService = copilotService;
    }

    /**
     * POST /api/v1/copilot/chat
     *
     * Body:
     * {
     *   "message": "Why did I overspend in March?",
     *   "history": [
     *     { "role": "user",      "content": "..." },
     *     { "role": "assistant", "content": "..." }
     *   ],
     *   "contextMonths": ["2025-03"]
     * }
     *
     * contextMonths — optional list of "YYYY-MM" strings.
     *
     * When to send contextMonths from the frontend:
     *   - User is on the March detail screen and taps "Ask Copilot"
     *     → send ["2025-03"]
     *   - User asks a comparison question about two months
     *     → send ["2025-01", "2025-10"]
     *   - User is on the home dashboard
     *     → omit contextMonths (overall + latest month is enough)
     *
     * The copilot ALWAYS gets:
     *   - UserAssessment
     *   - OverallProfile
     *   - Latest month
     *
     * The copilot additionally gets:
     *   - Any months in contextMonths (deduplicated against latest)
     */
    @PostMapping("/chat")
    public ResponseEntity<CopilotMessageDto> chat(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody ChatRequest body) {

        CopilotMessageDto response = copilotService.chat(
                phoneNumber,
                body.getMessage(),
                body.getHistory(),
                body.getContextMonths());

        return ResponseEntity.ok(response);
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    public static class ChatRequest {
        private String message;
        private List<Map<String, String>> history;
        private List<String> contextMonths;   // e.g. ["2025-03"] or ["2025-01","2025-10"]

        public String getMessage()                         { return message; }
        public void setMessage(String v)                   { this.message = v; }

        public List<Map<String, String>> getHistory()      { return history; }
        public void setHistory(List<Map<String, String>> v){ this.history = v; }

        public List<String> getContextMonths()             { return contextMonths; }
        public void setContextMonths(List<String> v)       { this.contextMonths = v; }
    }
}