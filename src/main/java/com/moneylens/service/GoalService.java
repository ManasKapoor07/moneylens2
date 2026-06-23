package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.profile.MonthlyProfileJson;
import com.moneylens.entity.FinancialGoal;
import com.moneylens.entity.OverallProfile;
import com.moneylens.entity.StatementProfile;
import com.moneylens.entity.User;
import com.moneylens.repository.FinancialGoalRepository;
import com.moneylens.repository.OverallProfileRepository;
import com.moneylens.repository.StatementProfileRepository;
import com.moneylens.dto.profile.OverallProfileJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class GoalService {

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model:gpt-4o}")
    private String model;

    private final FinancialGoalRepository goalRepository;
    private final OverallProfileRepository overallProfileRepository;
    private final StatementProfileRepository statementProfileRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public GoalService(
            FinancialGoalRepository goalRepository,
            OverallProfileRepository overallProfileRepository,
            StatementProfileRepository statementProfileRepository,
            ObjectMapper objectMapper) {
        this.goalRepository             = goalRepository;
        this.overallProfileRepository   = overallProfileRepository;
        this.statementProfileRepository = statementProfileRepository;
        this.objectMapper               = objectMapper;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public FinancialGoal create(User user, String title, String emoji,
                                BigDecimal targetAmount, LocalDate targetDate, String frequency) {
        FinancialGoal goal = new FinancialGoal();
        goal.setUser(user);
        goal.setTitle(title);
        goal.setEmoji(emoji != null ? emoji : pickEmoji(title));
        goal.setTargetAmount(targetAmount);
        goal.setSavedAmount(BigDecimal.ZERO);
        goal.setTargetDate(targetDate);
        goal.setFrequency(frequency != null ? frequency.toUpperCase() : "MONTHLY");
        goal = goalRepository.save(goal);

        // Generate plan immediately
        String plan = buildPlan(user.getId(), goal);
        goal.setAiPlan(plan);
        return goalRepository.save(goal);
    }

    public List<FinancialGoal> listForUser(Long userId) {
        return goalRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<FinancialGoal> findByIdAndUser(Long goalId, Long userId) {
        return goalRepository.findByIdAndUserId(goalId, userId);
    }

    public FinancialGoal update(FinancialGoal goal, String title, String emoji,
                               BigDecimal targetAmount, LocalDate targetDate) {
        if (title != null && !title.isBlank()) goal.setTitle(title.trim());
        if (emoji != null && !emoji.isBlank()) goal.setEmoji(emoji.trim());
        if (targetAmount != null && targetAmount.compareTo(BigDecimal.ZERO) > 0)
            goal.setTargetAmount(targetAmount);
        goal.setTargetDate(targetDate); // null = clear the date
        // Regenerate plan since target/date changed
        String plan = buildPlan(goal.getUser().getId(), goal);
        goal.setAiPlan(plan);
        return goalRepository.save(goal);
    }

    public FinancialGoal updateProgress(FinancialGoal goal, BigDecimal savedAmount) {
        goal.setSavedAmount(savedAmount);
        if (savedAmount.compareTo(goal.getTargetAmount()) >= 0) {
            goal.setStatus("COMPLETED");
        }
        return goalRepository.save(goal);
    }

    public FinancialGoal updateFrequency(FinancialGoal goal, String frequency) {
        goal.setFrequency(frequency.toUpperCase());
        String plan = buildPlan(goal.getUser().getId(), goal);
        goal.setAiPlan(plan);
        return goalRepository.save(goal);
    }

    public void delete(FinancialGoal goal) {
        goalRepository.delete(goal);
    }

    // ── Plan generation ───────────────────────────────────────────────────────

    public String buildPlan(Long userId, FinancialGoal goal) {
        try {
            // Financial data
            BigDecimal income     = getAvgIncome(userId);
            BigDecimal avgSavings = getAvgSavings(userId);
            BigDecimal remaining  = goal.getTargetAmount().subtract(
                goal.getSavedAmount() != null ? goal.getSavedAmount() : BigDecimal.ZERO);

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                return completedPlan(goal);
            }

            boolean monthly = !"WEEKLY".equalsIgnoreCase(goal.getFrequency());

            // How many periods until goal?
            int periods;
            BigDecimal perPeriod;

            if (goal.getTargetDate() != null && goal.getTargetDate().isAfter(LocalDate.now())) {
                long months = ChronoUnit.MONTHS.between(LocalDate.now(), goal.getTargetDate());
                if (months < 1) months = 1;
                periods   = monthly ? (int) months : (int) (months * 4);
                perPeriod = remaining.divide(BigDecimal.valueOf(periods), 0, RoundingMode.CEILING);
            } else {
                // No target date — use current savings rate
                if (avgSavings == null || avgSavings.compareTo(BigDecimal.valueOf(500)) <= 0) {
                    avgSavings = income.multiply(BigDecimal.valueOf(0.15));
                }
                perPeriod = monthly ? avgSavings : avgSavings.divide(BigDecimal.valueOf(4), 0, RoundingMode.CEILING);
                if (perPeriod.compareTo(BigDecimal.ZERO) <= 0) perPeriod = BigDecimal.valueOf(1000);
                periods = remaining.divide(perPeriod, 0, RoundingMode.CEILING).intValue();
            }

            // Cap at 120 months / 480 weeks to avoid absurd estimates
            periods = Math.min(periods, monthly ? 120 : 480);

            // Milestones
            List<Map<String, Object>> milestones = new ArrayList<>();
            int[] checkpoints = {25, 50, 75, 100};
            String[] labels   = {"Quarter way there", "Halfway! 🎯", "Almost there!", "Goal reached! 🎉"};
            for (int i = 0; i < checkpoints.length; i++) {
                BigDecimal amt = goal.getTargetAmount()
                        .multiply(BigDecimal.valueOf(checkpoints[i]))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                int p = amt.divide(perPeriod, 0, RoundingMode.CEILING).intValue();
                Map<String, Object> ms = new LinkedHashMap<>();
                ms.put("percentage", checkpoints[i]);
                ms.put("amount", amt.longValue());
                ms.put("period", Math.min(p, periods));
                ms.put("label", labels[i]);
                milestones.add(ms);
            }

            // Feasibility
            String feasibility;
            if (income.compareTo(BigDecimal.ZERO) > 0) {
                double pctOfIncome = perPeriod.doubleValue() / (monthly ? income.doubleValue() : income.doubleValue() / 4);
                feasibility = pctOfIncome < 0.10 ? "EASY" : pctOfIncome < 0.25 ? "COMFORTABLE" : pctOfIncome < 0.40 ? "TIGHT" : "CHALLENGING";
            } else {
                feasibility = "COMFORTABLE";
            }

            // AI-generated personalised tips
            List<String> tips = buildTips(userId, goal, perPeriod, periods, monthly, feasibility);

            // Target date estimate
            LocalDate estimatedDate = monthly
                    ? LocalDate.now().plusMonths(periods)
                    : LocalDate.now().plusWeeks(periods);
            String[] monthNames = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
            String dateEstimate = monthNames[estimatedDate.getMonthValue() - 1] + " " + estimatedDate.getYear();

            // Assemble plan JSON
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("perPeriod",        perPeriod.longValue());
            plan.put("periods",          periods);
            plan.put("frequency",        monthly ? "MONTHLY" : "WEEKLY");
            plan.put("targetDateEstimate", goal.getTargetDate() != null
                    ? goal.getTargetDate().toString() : estimatedDate.toString());
            plan.put("targetDateLabel",  dateEstimate);
            plan.put("feasibility",      feasibility);
            plan.put("milestones",       milestones);
            plan.put("tips",             tips);

            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String completedPlan(FinancialGoal goal) {
        try {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("perPeriod", 0);
            plan.put("periods", 0);
            plan.put("frequency", goal.getFrequency());
            plan.put("feasibility", "COMPLETED");
            plan.put("milestones", List.of());
            plan.put("tips", List.of("You've already saved enough for this goal! 🎉"));
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) { return "{}"; }
    }

    private List<String> buildTips(Long userId, FinancialGoal goal,
                                    BigDecimal perPeriod, int periods, boolean monthly,
                                    String feasibility) {
        try {
            StatementProfile latest = statementProfileRepository
                    .findTopByUserIdOrderByProfileYearDescProfileMonthDesc(userId)
                    .orElse(null);
            MonthlyProfileJson mp = (latest != null && latest.getProfileJson() != null)
                    ? objectMapper.readValue(latest.getProfileJson(), MonthlyProfileJson.class) : null;

            // Build rich context for the AI
            StringBuilder ctx = new StringBuilder();
            ctx.append("GOAL: ").append(goal.getTitle())
               .append(" | Target: ₹").append(String.format("%,.0f", goal.getTargetAmount().doubleValue()))
               .append(" | Already saved: ₹").append(String.format("%,.0f",
                       goal.getSavedAmount() != null ? goal.getSavedAmount().doubleValue() : 0))
               .append("\n");
            ctx.append("PLAN: Save ₹").append(String.format("%,.0f", perPeriod.doubleValue()))
               .append(monthly ? "/month" : "/week")
               .append(" for ").append(periods).append(monthly ? " months" : " weeks")
               .append(" | Feasibility: ").append(feasibility).append("\n");

            BigDecimal income = getAvgIncome(userId);
            ctx.append("USER INCOME: ₹").append(String.format("%,.0f", income.doubleValue())).append("/month\n");

            if (mp != null) {
                if (mp.getFinancials() != null) {
                    double debits = mp.getFinancials().totalDebits != null
                            ? mp.getFinancials().totalDebits.doubleValue() : 0;
                    ctx.append("MONTHLY SPEND: ₹").append(String.format("%,.0f", debits))
                       .append(" | Lifestyle ratio: ").append(String.format("%.0f%%", mp.getFinancials().lifestyleRatio)).append("\n");
                }
                if (mp.getSpendingBreakdown() != null && !mp.getSpendingBreakdown().isEmpty()) {
                    ctx.append("SPENDING BY CATEGORY: ");
                    mp.getSpendingBreakdown().entrySet().stream()
                      .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                      .limit(6)
                      .forEach(e -> ctx.append(e.getKey()).append(":₹")
                              .append(String.format("%,.0f", e.getValue().doubleValue())).append(" "));
                    ctx.append("\n");
                }
                if (mp.getTopMerchants() != null && !mp.getTopMerchants().isEmpty()) {
                    ctx.append("TOP MERCHANTS: ");
                    mp.getTopMerchants().stream().limit(5).forEach(m ->
                            ctx.append(m.name).append(":₹").append(
                                    m.amount != null ? String.format("%,.0f", m.amount.doubleValue()) : "?").append(" "));
                    ctx.append("\n");
                }
                if (mp.getRecurringPayments() != null && !mp.getRecurringPayments().isEmpty()) {
                    long subs = mp.getRecurringPayments().stream().filter(r -> "SUBSCRIPTION".equals(r.type)).count();
                    BigDecimal subTotal = mp.getRecurringPayments().stream()
                            .filter(r -> "SUBSCRIPTION".equals(r.type) && r.amount != null)
                            .map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    ctx.append("SUBSCRIPTIONS: ").append(subs).append(" active, total ₹")
                       .append(String.format("%,.0f", subTotal.doubleValue())).append("/month\n");
                    ctx.append("RECURRING DETAIL: ");
                    mp.getRecurringPayments().stream().limit(5).forEach(r ->
                            ctx.append(r.merchant).append("[").append(r.type).append("]:₹")
                               .append(r.amount != null ? String.format("%,.0f", r.amount.doubleValue()) : "?").append(" "));
                    ctx.append("\n");
                }
                int salaryDay = mp.getSalaryRunway() != null && mp.getSalaryRunway().salaryDay != null
                        ? mp.getSalaryRunway().salaryDay : 0;
                if (salaryDay > 0) ctx.append("SALARY CREDITED: ").append(ordinal(salaryDay)).append(" of month\n");
                if (mp.getWeeklyPattern() != null && mp.getWeeklyPattern().pattern != null)
                    ctx.append("WEEKLY PATTERN: ").append(mp.getWeeklyPattern().pattern)
                       .append(" — ").append(mp.getWeeklyPattern().insight).append("\n");
            }

            String prompt = """
                    You are a sharp Indian personal finance advisor. The user has set a savings goal.
                    Based on their ACTUAL spending data below, write exactly 3 tips to help them reach it.

                    Rules:
                    - Each tip must be 1-2 sentences, direct and specific — use actual merchant names, amounts, and numbers from the data
                    - At least one tip must reference a specific merchant or category they overspend on
                    - At least one tip must be about automation (specific day, specific amount)
                    - Avoid generic advice like "spend less" or "save more" — be concrete with ₹ numbers
                    - Tone: friendly advisor, not a lecture. Use Indian context (salary day, UPI, SIP, etc.)
                    - Output JSON array of 3 strings only, no keys, no extra text

                    Example format: ["tip one", "tip two", "tip three"]

                    USER DATA:
                    """ + ctx;

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", prompt)
            );
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model",       model);
            body.put("max_tokens",  400);
            body.put("temperature", 0.8);
            body.put("messages",    messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    OPENAI_URL, new HttpEntity<>(body, headers), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (choices != null && !choices.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                String content = String.valueOf(msg.get("content")).trim();
                // Strip markdown code fences if present
                if (content.startsWith("```")) {
                    content = content.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
                }
                @SuppressWarnings("unchecked")
                List<String> parsed = objectMapper.readValue(content, List.class);
                if (parsed != null && !parsed.isEmpty()) return parsed;
            }
        } catch (Exception e) {
            log.warn("AI tip generation failed for goal {}: {}", goal.getId(), e.getMessage());
        }
        // Fallback: minimal static tips if AI fails
        return List.of(
                String.format("Set up an auto-transfer of ₹%,.0f right after your salary arrives.", perPeriod.doubleValue()),
                "Review your subscriptions — cancelling unused ones can free up ₹500–2,000/month.",
                "Track weekly spend every Sunday to catch overspending before it derails your plan."
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal getAvgIncome(Long userId) {
        return overallProfileRepository.findByUserId(userId)
                .map(OverallProfile::getAvgIncome)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .orElse(BigDecimal.valueOf(50000));
    }

    private BigDecimal getAvgSavings(Long userId) {
        return overallProfileRepository.findByUserId(userId)
                .map(OverallProfile::getProfileJson)
                .filter(json -> json != null && !json.isBlank())
                .map(json -> {
                    try {
                        OverallProfileJson op = objectMapper.readValue(json, OverallProfileJson.class);
                        return op.getAvgFinancials() != null ? op.getAvgFinancials().avgSavings : null;
                    } catch (Exception e) { return null; }
                })
                .orElse(null);
    }

    private String pickEmoji(String title) {
        if (title == null) return "🎯";
        String t = title.toLowerCase();
        if (t.contains("iphone") || t.contains("phone") || t.contains("mobile")) return "📱";
        if (t.contains("laptop") || t.contains("macbook") || t.contains("computer")) return "💻";
        if (t.contains("car") || t.contains("bike") || t.contains("vehicle")) return "🚗";
        if (t.contains("goa") || t.contains("trip") || t.contains("travel") || t.contains("vacation")) return "✈️";
        if (t.contains("home") || t.contains("house") || t.contains("flat") || t.contains("rent")) return "🏠";
        if (t.contains("wedding") || t.contains("marriage")) return "💍";
        if (t.contains("emergency") || t.contains("fund") || t.contains("savings")) return "🛡️";
        if (t.contains("edu") || t.contains("course") || t.contains("study")) return "📚";
        if (t.contains("watch")) return "⌚";
        if (t.contains("tv") || t.contains("television")) return "📺";
        return "🎯";
    }

    private String ordinal(int n) {
        if (n >= 11 && n <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    // ── Summary for AI context ────────────────────────────────────────────────

    public String buildGoalsSummary(Long userId) {
        List<FinancialGoal> goals = goalRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (goals.isEmpty()) return "No financial goals set yet.";

        StringBuilder sb = new StringBuilder();
        for (FinancialGoal g : goals) {
            BigDecimal saved  = g.getSavedAmount() != null ? g.getSavedAmount() : BigDecimal.ZERO;
            BigDecimal target = g.getTargetAmount();
            int pct = target.compareTo(BigDecimal.ZERO) > 0
                    ? saved.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.HALF_UP).intValue()
                    : 0;
            sb.append(String.format("• %s %s — target ₹%,.0f, saved ₹%,.0f (%d%%), status: %s\n",
                    g.getEmoji() != null ? g.getEmoji() : "🎯",
                    g.getTitle(), target.doubleValue(), saved.doubleValue(), pct, g.getStatus()));
        }
        return sb.toString().trim();
    }
}
