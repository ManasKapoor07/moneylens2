package com.moneylens.controller;

import com.moneylens.entity.*;
import com.moneylens.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final RecurringPaymentRepository recurringPaymentRepository;
    private final StatementProfileRepository statementProfileRepository;
    private final OverallProfileRepository overallProfileRepository;

    public DashboardController(
            UserRepository userRepository,
            TransactionRepository transactionRepository,
            RecurringPaymentRepository recurringPaymentRepository,
            StatementProfileRepository statementProfileRepository,
            OverallProfileRepository overallProfileRepository) {

        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.recurringPaymentRepository = recurringPaymentRepository;
        this.statementProfileRepository = statementProfileRepository;
        this.overallProfileRepository = overallProfileRepository;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/dashboard/spending-breakdown?year=2026&month=5
    // Pie chart data — category wise spend
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/spending-breakdown")
    public ResponseEntity<?> getSpendingBreakdown(
            @AuthenticationPrincipal String phoneNumber,
            @RequestParam int year,
            @RequestParam int month) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Transaction> transactions = transactionRepository
                .findByUserIdAndStatementYearAndStatementMonth(
                        user.getId(), year, month);

        // Group DEBIT transactions by category → sum amounts
        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();

        transactions.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> !t.isRefund())
                .forEach(t -> {
                    // Use userCategory if set, else systemCategory
                    Category cat = t.getUserCategory() != null
                            ? t.getUserCategory()
                            : t.getSystemCategory();

                    String displayName = cat != null
                            ? cat.getDisplayName()
                            : "Other";

                    breakdown.merge(displayName,
                            t.getWithdrawalAmount() != null
                                    ? t.getWithdrawalAmount()
                                    : BigDecimal.ZERO,
                            BigDecimal::add);
                });

        // Sort by amount descending
        List<Map<String, Object>> result = breakdown.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", e.getKey());
                    m.put("amount", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        // Total spend
        BigDecimal total = result.stream()
                .map(m -> (BigDecimal) m.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Add percentage to each
        result.forEach(m -> {
            BigDecimal amount = (BigDecimal) m.get("amount");
            double pct = total.compareTo(BigDecimal.ZERO) > 0
                    ? amount.doubleValue() / total.doubleValue() * 100
                    : 0;
            m.put("percentage", Math.round(pct * 10.0) / 10.0);
        });

        return ResponseEntity.ok(Map.of(
                "year", year,
                "month", month,
                "totalSpend", total,
                "breakdown", result
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/dashboard/top-merchants?year=2026&month=5&limit=5
    // Top merchants by spend
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/top-merchants")
    public ResponseEntity<?> getTopMerchants(
            @AuthenticationPrincipal String phoneNumber,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(defaultValue = "5") int limit) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Transaction> transactions = transactionRepository
                .findByUserIdAndStatementYearAndStatementMonth(
                        user.getId(), year, month);

        // Group by merchantName → sum
        Map<String, BigDecimal> merchantMap = new LinkedHashMap<>();

        transactions.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> !t.isRefund())
                .filter(t -> t.getMerchantName() != null
                        && !t.getMerchantName().isBlank())
                .forEach(t -> merchantMap.merge(
                        t.getMerchantName(),
                        t.getWithdrawalAmount() != null
                                ? t.getWithdrawalAmount()
                                : BigDecimal.ZERO,
                        BigDecimal::add));

        List<Map<String, Object>> result = merchantMap.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("merchant", e.getKey());
                    m.put("amount", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "year", year,
                "month", month,
                "merchants", result
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/dashboard/recurring
    // All recurring payments — sorted by amount desc
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/recurring")
    public ResponseEntity<?> getRecurring(
            @AuthenticationPrincipal String phoneNumber) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Get latest month's recurring payments
        // (OverallProfileService already deduplicates and upgrades confidence)
        List<RecurringPayment> all = recurringPaymentRepository
                .findByUserIdOrderByAmountDesc(user.getId());

        // Deduplicate by merchantKey — keep latest/highest confidence
        Map<String, RecurringPayment> deduped = new LinkedHashMap<>();
        for (RecurringPayment rp : all) {
            deduped.merge(rp.getMerchantKey(), rp, (existing, incoming) -> {
                // Prefer higher confidence
                if (incoming.getConfidence().ordinal()
                        > existing.getConfidence().ordinal()) {
                    return incoming;
                }
                return existing;
            });
        }

        List<Map<String, Object>> result = deduped.values().stream()
                .sorted(Comparator.comparing(RecurringPayment::getAmount).reversed())
                .map(rp -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("merchant",           rp.getMerchant());
                    m.put("amount",             rp.getAmount());
                    m.put("type",               rp.getRecurringType().name());
                    m.put("confidence",         rp.getConfidence().name());
                    m.put("monthsDetected",     rp.getMonthsDetected());
                    m.put("declaredInAssessment", rp.isDeclaredInAssessment());
                    m.put("isUndeclared",       !rp.isDeclaredInAssessment());
                    return m;
                })
                .collect(Collectors.toList());

        long undeclaredCount = result.stream()
                .filter(m -> (boolean) m.get("isUndeclared"))
                .count();

        BigDecimal undeclaredTotal = deduped.values().stream()
                .filter(rp -> !rp.isDeclaredInAssessment())
                .map(RecurringPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(Map.of(
                "recurring",        result,
                "undeclaredCount",  undeclaredCount,
                "undeclaredTotal",  undeclaredTotal
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/dashboard/insights?year=2026&month=5
    // Behavioral insight cards for dashboard
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/insights")
    public ResponseEntity<?> getInsights(
            @AuthenticationPrincipal String phoneNumber,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // If no year/month → use latest month
        StatementProfile profile;
        if (year != null && month != null) {
            profile = statementProfileRepository
                    .findByUserIdAndProfileYearAndProfileMonth(
                            user.getId(), year, month)
                    .orElse(null);
        } else {
            profile = statementProfileRepository
                    .findTopByUserIdOrderByProfileYearDescProfileMonthDesc(
                            user.getId())
                    .orElse(null);
        }

        if (profile == null) {
            return ResponseEntity.ok(Map.of(
                    "hasData", false,
                    "insights", List.of()
            ));
        }

        List<Map<String, Object>> insights = new ArrayList<>();

        // Insight 1: Money gone fast
        if (profile.getRunwayDays() != null && profile.getRunwayDays() <= 15) {
            insights.add(Map.of(
                    "type",    "RUNWAY",
                    "icon",    "⚡",
                    "title",   "Money gone in " + profile.getRunwayDays() + " days",
                    "detail",  "60% of your salary was spent within "
                            + profile.getRunwayDays() + " days of credit",
                    "severity","HIGH"
            ));
        }

        // Insight 2: Hidden EMIs
        if (profile.isHiddenDebtFound()) {
            // Get undeclared total from recurring
            List<RecurringPayment> undeclared = recurringPaymentRepository
                    .findByUserIdAndDeclaredInAssessmentFalseAndConfidenceIn(
                            user.getId(),
                            List.of(RecurringPayment.Confidence.LIKELY,
                                    RecurringPayment.Confidence.CONFIRMED));

            BigDecimal hiddenTotal = undeclared.stream()
                    .map(RecurringPayment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            insights.add(Map.of(
                    "type",    "HIDDEN_DEBT",
                    "icon",    "💸",
                    "title",   "₹" + hiddenTotal.toPlainString() + " hidden EMIs found",
                    "detail",  undeclared.size() + " undeclared recurring payments detected",
                    "severity","HIGH"
            ));
        }

        // Insight 3: Income discrepancy
        if (profile.isIncomeDiscrepancyFound()) {
            insights.add(Map.of(
                    "type",    "INCOME_DISCREPANCY",
                    "icon",    "📊",
                    "title",   "Income doesn't match what you declared",
                    "detail",  "Actual credits differ significantly from your assessment",
                    "severity","MEDIUM"
            ));
        }

        // Insight 4: Savings overstated
        if (profile.isSavingsOverstated()) {
            insights.add(Map.of(
                    "type",    "SAVINGS_OVERSTATED",
                    "icon",    "⚠️",
                    "title",   "You're saving less than you think",
                    "detail",  "Your declared savings don't match actual cash flow",
                    "severity","HIGH"
            ));
        }

        // Insight 5: Weekly pattern
        if ("BACK_HEAVY".equals(profile.getWeeklyPattern())) {
            insights.add(Map.of(
                    "type",    "WEEKLY_PATTERN",
                    "icon",    "📅",
                    "title",   "You spend more at month-end",
                    "detail",  "Back-heavy pattern — possible bill pile-up or cash crunch",
                    "severity","MEDIUM"
            ));
        } else if ("FRONT_HEAVY".equals(profile.getWeeklyPattern())) {
            insights.add(Map.of(
                    "type",    "WEEKLY_PATTERN",
                    "icon",    "📅",
                    "title",   "Post-salary spending surge",
                    "detail",  "Heavy spending in week 1 — budget your first week carefully",
                    "severity","MEDIUM"
            ));
        }

        // Insight 6: Post salary surge
        if (profile.isPostSalarySurge()) {
            insights.add(Map.of(
                    "type",    "POST_SALARY_SURGE",
                    "icon",    "🚀",
                    "title",   "Salary day splurge detected",
                    "detail",  "Heavy spending within 3 days of salary credit",
                    "severity","MEDIUM"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "hasData",  true,
                "year",     profile.getProfileYear(),
                "month",    profile.getProfileMonth(),
                "insights", insights
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/dashboard/sub-scores?year=2026&month=5
    // 6 sub-scores for the radar/bar chart
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/sub-scores")
    public ResponseEntity<?> getSubScores(
            @AuthenticationPrincipal String phoneNumber,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        StatementProfile profile;
        if (year != null && month != null) {
            profile = statementProfileRepository
                    .findByUserIdAndProfileYearAndProfileMonth(
                            user.getId(), year, month)
                    .orElse(null);
        } else {
            profile = statementProfileRepository
                    .findTopByUserIdOrderByProfileYearDescProfileMonthDesc(
                            user.getId())
                    .orElse(null);
        }

        if (profile == null) {
            return ResponseEntity.ok(Map.of("hasData", false));
        }

        List<Map<String, Object>> scores = List.of(
                scoreMap("Savings Rate",        profile.getSavingsRateScore()),
                scoreMap("Spending Discipline", profile.getSpendingDisciplineScore()),
                scoreMap("Debt Burden",         profile.getDebtBurdenScore()),
                scoreMap("Income Stability",    profile.getIncomeStabilityScore()),
                scoreMap("Emergency Cushion",   profile.getEmergencyCushionScore()),
                scoreMap("Goal Alignment",      profile.getGoalAlignmentScore())
        );

        return ResponseEntity.ok(Map.of(
                "hasData",     true,
                "healthScore", profile.getHealthScore(),
                "archetype",   profile.getArchetype(),
                "scores",      scores
        ));
    }

    private Map<String, Object> scoreMap(String label, int score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("score", score);
        m.put("grade", score >= 80 ? "GOOD"
                : score >= 60 ? "FAIR"
                : "POOR");
        return m;
    }
}