package com.moneylens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.entity.FinancialGoal;
import com.moneylens.entity.User;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.GoalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalService    goalService;
    private final UserRepository userRepository;
    private final ObjectMapper   objectMapper;

    public GoalController(GoalService goalService, UserRepository userRepository, ObjectMapper objectMapper) {
        this.goalService    = goalService;
        this.userRepository = userRepository;
        this.objectMapper   = objectMapper;
    }

    // POST /api/v1/goals — create a new goal
    @PostMapping
    public ResponseEntity<?> createGoal(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody CreateGoalRequest req) {

        User user = resolveUser(phoneNumber);
        try {
            LocalDate targetDate = req.targetDate != null ? LocalDate.parse(req.targetDate) : null;
            FinancialGoal goal = goalService.create(
                    user, req.title, req.emoji,
                    new BigDecimal(String.valueOf(req.targetAmount)),
                    targetDate, req.frequency);
            return ResponseEntity.ok(toMap(goal));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // GET /api/v1/goals — list all goals
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listGoals(
            @AuthenticationPrincipal String phoneNumber) {

        User user = resolveUser(phoneNumber);
        List<Map<String, Object>> result = goalService.listForUser(user.getId())
                .stream().map(this::toMap).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // PATCH /api/v1/goals/{id} — edit title, emoji, targetAmount, targetDate
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateGoal(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        User user = resolveUser(phoneNumber);
        return goalService.findByIdAndUser(id, user.getId())
                .map(goal -> {
                    try {
                        String title  = body.containsKey("title")  ? String.valueOf(body.get("title"))  : null;
                        String emoji  = body.containsKey("emoji")  ? String.valueOf(body.get("emoji"))  : null;
                        BigDecimal amt = body.containsKey("targetAmount")
                                ? new BigDecimal(String.valueOf(body.get("targetAmount"))) : null;
                        LocalDate date = body.containsKey("targetDate") && body.get("targetDate") != null
                                && !String.valueOf(body.get("targetDate")).equals("null")
                                && !String.valueOf(body.get("targetDate")).isBlank()
                                ? LocalDate.parse(String.valueOf(body.get("targetDate"))) : null;
                        FinancialGoal updated = goalService.update(goal, title, emoji, amt, date);
                        return ResponseEntity.ok(toMap(updated));
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // PATCH /api/v1/goals/{id}/progress — update saved amount
    @PatchMapping("/{id}/progress")
    public ResponseEntity<?> updateProgress(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        User user = resolveUser(phoneNumber);
        return goalService.findByIdAndUser(id, user.getId())
                .map(goal -> {
                    BigDecimal saved = new BigDecimal(String.valueOf(body.get("savedAmount")));
                    FinancialGoal updated = goalService.updateProgress(goal, saved);
                    return ResponseEntity.ok(toMap(updated));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // PATCH /api/v1/goals/{id}/frequency — switch monthly/weekly plan
    @PatchMapping("/{id}/frequency")
    public ResponseEntity<?> updateFrequency(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        User user = resolveUser(phoneNumber);
        return goalService.findByIdAndUser(id, user.getId())
                .map(goal -> {
                    String freq = String.valueOf(body.get("frequency"));
                    FinancialGoal updated = goalService.updateFrequency(goal, freq);
                    return ResponseEntity.ok(toMap(updated));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // DELETE /api/v1/goals/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGoal(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable Long id) {

        User user = resolveUser(phoneNumber);
        return goalService.findByIdAndUser(id, user.getId())
                .map(goal -> {
                    goalService.delete(goal);
                    return ResponseEntity.ok(Map.of("deleted", true));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User resolveUser(String phoneNumber) {
        return userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private Map<String, Object> toMap(FinancialGoal g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            g.getId());
        m.put("title",         g.getTitle());
        m.put("emoji",         g.getEmoji());
        m.put("targetAmount",  g.getTargetAmount());
        m.put("savedAmount",   g.getSavedAmount() != null ? g.getSavedAmount() : BigDecimal.ZERO);
        m.put("targetDate",    g.getTargetDate() != null ? g.getTargetDate().toString() : null);
        m.put("frequency",     g.getFrequency());
        m.put("status",        g.getStatus());
        m.put("createdAt",     g.getCreatedAt() != null ? g.getCreatedAt().toString() : null);

        // Parse aiPlan JSON back into object so frontend gets structured data
        if (g.getAiPlan() != null && !g.getAiPlan().isBlank()) {
            try {
                m.put("plan", objectMapper.readValue(g.getAiPlan(), Map.class));
            } catch (Exception e) {
                m.put("plan", null);
            }
        } else {
            m.put("plan", null);
        }
        return m;
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public static class CreateGoalRequest {
        public String title;
        public String emoji;
        public double targetAmount;
        public String targetDate;  // YYYY-MM-DD or null
        public String frequency;   // MONTHLY | WEEKLY
    }
}
