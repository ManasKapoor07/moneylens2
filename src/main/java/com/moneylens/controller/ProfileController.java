package com.moneylens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.profile.MonthlyProfileJson;
import com.moneylens.dto.profile.OverallProfileJson;
import com.moneylens.entity.*;
import com.moneylens.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final UserRepository              userRepository;
    private final OverallProfileRepository    overallProfileRepository;
    private final StatementProfileRepository  statementProfileRepository;
    private final ObjectMapper                objectMapper;

    public ProfileController(
            UserRepository userRepository,
            OverallProfileRepository overallProfileRepository,
            StatementProfileRepository statementProfileRepository,
            ObjectMapper objectMapper) {

        this.userRepository            = userRepository;
        this.overallProfileRepository  = overallProfileRepository;
        this.statementProfileRepository = statementProfileRepository;
        this.objectMapper              = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/profile/overall
    // Home dashboard — returns deserialized OverallProfileJson
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/overall")
    public ResponseEntity<?> getOverallProfile(
            @AuthenticationPrincipal String phoneNumber) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        OverallProfile entity = overallProfileRepository.findByUserId(user.getId())
                .orElse(null);

        if (entity == null || entity.getProfileJson() == null) {
            return ResponseEntity.ok(Map.of(
                    "hasData", false,
                    "message", "No statements uploaded yet. Upload your bank statement to get started."
            ));
        }

        try {
            OverallProfileJson profile = objectMapper.readValue(
                    entity.getProfileJson(), OverallProfileJson.class);

            // Wrap with hasData flag
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("hasData",        true);
            response.put("lastRefreshedAt", entity.getLastRefreshedAt());
            response.put("profile",         profile);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to load profile: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/profile/month/{year}/{month}
    // Month detail screen — returns deserialized MonthlyProfileJson
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/month/{year}/{month}")
    public ResponseEntity<?> getMonthProfile(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable int year,
            @PathVariable int month) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        StatementProfile entity = statementProfileRepository
                .findByUserIdAndProfileYearAndProfileMonth(user.getId(), year, month)
                .orElse(null);

        if (entity == null || entity.getProfileJson() == null) {
            return ResponseEntity.ok(Map.of(
                    "hasData", false,
                    "message", String.format("No data for %04d-%02d", year, month)
            ));
        }

        try {
            MonthlyProfileJson profile = objectMapper.readValue(
                    entity.getProfileJson(), MonthlyProfileJson.class);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("hasData",    true);
            response.put("statementId", entity.getStatement() != null
                    ? entity.getStatement().getId() : null);
            response.put("createdAt",  entity.getCreatedAt());
            response.put("profile",    profile);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to load month profile: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/profile/months
    // Navigation list — lightweight summary of all available months
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/months")
    public ResponseEntity<List<Map<String, Object>>> getAvailableMonths(
            @AuthenticationPrincipal String phoneNumber) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<StatementProfile> profiles = statementProfileRepository
                .findByUserIdOrderByProfileYearDescProfileMonthDesc(user.getId());

        List<Map<String, Object>> result = profiles.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year",         p.getProfileYear());
            m.put("month",        p.getProfileMonth());
            m.put("yearMonth",    String.format("%04d-%02d", p.getProfileYear(), p.getProfileMonth()));
            m.put("healthScore",  p.getHealthScore());
            m.put("archetype",    p.getArchetype());
            m.put("totalSpend",   p.getTotalSpend());
            m.put("actualSavings",p.getActualSavings());
            m.put("transactionCount", p.getTransactionCount());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/profile/weekly-trend
    // All weeks across all months — used for multi-month spending trend chart
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/weekly-trend")
    public ResponseEntity<Map<String, Object>> getWeeklyTrend(
            @AuthenticationPrincipal String phoneNumber) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<StatementProfile> profiles = statementProfileRepository
                .findByUserIdOrderByProfileYearDescProfileMonthDesc(user.getId());

        // Chronological order (oldest first)
        Collections.reverse(profiles);

        String[] monthNames = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                               "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

        List<Map<String, Object>> weeks      = new ArrayList<>();
        List<Map<String, Object>> boundaries = new ArrayList<>();

        for (StatementProfile p : profiles) {
            int startIdx = weeks.size();
            String shortLabel = monthNames[p.getProfileMonth()] + " " + p.getProfileYear();
            String yearMonth  = String.format("%04d-%02d", p.getProfileYear(), p.getProfileMonth());

            BigDecimal[] vals = {
                p.getWeek1Spend() != null ? p.getWeek1Spend() : BigDecimal.ZERO,
                p.getWeek2Spend() != null ? p.getWeek2Spend() : BigDecimal.ZERO,
                p.getWeek3Spend() != null ? p.getWeek3Spend() : BigDecimal.ZERO,
                p.getWeek4Spend() != null ? p.getWeek4Spend() : BigDecimal.ZERO,
            };

            for (int w = 0; w < 4; w++) {
                Map<String, Object> wk = new LinkedHashMap<>();
                wk.put("label",     shortLabel + " W" + (w + 1));
                wk.put("shortLabel","W" + (w + 1));
                wk.put("yearMonth", yearMonth);
                wk.put("weekNum",   w + 1);
                wk.put("amount",    vals[w].doubleValue());
                weeks.add(wk);
            }

            Map<String, Object> boundary = new LinkedHashMap<>();
            boundary.put("yearMonth",   yearMonth);
            boundary.put("label",       shortLabel);
            boundary.put("startIndex",  startIdx);
            boundary.put("pattern",     p.getWeeklyPattern());
            boundary.put("totalSpend",  p.getTotalSpend());
            boundaries.add(boundary);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("weeks",       weeks);
        response.put("boundaries",  boundaries);
        response.put("monthCount",  profiles.size());
        return ResponseEntity.ok(response);
    }
}