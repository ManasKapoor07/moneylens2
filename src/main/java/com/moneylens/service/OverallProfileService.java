package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.profile.MonthlyProfileJson;
import com.moneylens.dto.profile.OverallProfileJson;
import com.moneylens.entity.*;
import com.moneylens.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OverallProfileService {

    private static final Logger log = LoggerFactory.getLogger(OverallProfileService.class);

    private static final int CONFIRMED_MONTHS = 4;
    private static final int LIKELY_MONTHS    = 2;
    private static final int TREND_THRESHOLD  = 5;

    private final OverallProfileRepository    overallProfileRepository;
    private final StatementProfileRepository  statementProfileRepository;
    private final RecurringPaymentRepository  recurringPaymentRepository;
    private final UserRepository              userRepository;
    private final UserAssessmentRepository    assessmentRepository;
    private final ObjectMapper                objectMapper;

    public OverallProfileService(
            OverallProfileRepository overallProfileRepository,
            StatementProfileRepository statementProfileRepository,
            RecurringPaymentRepository recurringPaymentRepository,
            UserRepository userRepository,
            UserAssessmentRepository assessmentRepository,
            ObjectMapper objectMapper) {

        this.overallProfileRepository  = overallProfileRepository;
        this.statementProfileRepository = statementProfileRepository;
        this.recurringPaymentRepository = recurringPaymentRepository;
        this.userRepository            = userRepository;
        this.assessmentRepository      = assessmentRepository;
        this.objectMapper              = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public void refresh(Long userId) {
        log.info("Refreshing OverallProfile for user {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);

        List<StatementProfile> profiles = statementProfileRepository
                .findByUserIdOrderByProfileYearDescProfileMonthDesc(userId);

        if (profiles.isEmpty()) {
            log.warn("No StatementProfiles for user {} — skipping OverallProfile refresh", userId);
            return;
        }

        // Sort oldest → newest for all computations
        List<StatementProfile> sorted = new ArrayList<>(profiles);
        sorted.sort(Comparator.comparingInt(StatementProfile::getProfileYear)
                .thenComparingInt(StatementProfile::getProfileMonth));

        // ── 1. Upgrade recurring confidence first ──────────────────────────
        upgradeRecurringConfidence(userId);

        // ── 2. Load confirmed recurring (after upgrade) ────────────────────
        List<RecurringPayment> confirmed = recurringPaymentRepository
                .findConfirmedByUserId(userId);
        List<RecurringPayment> undeclared = recurringPaymentRepository
                .findUndeclaredByUserId(userId);

        // De-dup confirmed by merchantKey — take most recent amount
        Map<String, RecurringPayment> latestConfirmed = deduplicateByKey(confirmed);

        // ── 3. Build OverallProfileJson ────────────────────────────────────
        OverallProfileJson json = buildOverallProfileJson(
                sorted, assessment, latestConfirmed, undeclared);

        // ── 4. Build/update OverallProfile entity ─────────────────────────
        OverallProfile overall = overallProfileRepository.findByUserId(userId)
                .orElse(new OverallProfile());
        overall.setUser(user);

        // Populate indexed columns for queryability
        overall.setMonthsAnalyzed(sorted.size());
        overall.setEarliestMonth(sorted.get(0).getFirstDayOfMonth());
        overall.setLatestMonth(sorted.get(sorted.size() - 1).getFirstDayOfMonth());
        overall.setAvgHealthScore(json.getAvgHealthScore());
        overall.setLatestHealthScore(json.getLatestHealthScore());
        overall.setBestHealthScore(json.getBestHealthScore());
        overall.setWorstHealthScore(json.getWorstHealthScore());
        overall.setAvgSavingsRateScore(json.getAvgSubScores().savingsRate);
        overall.setAvgSpendingDisciplineScore(json.getAvgSubScores().spendingDiscipline);
        overall.setAvgDebtBurdenScore(json.getAvgSubScores().debtBurden);
        overall.setAvgIncomeStabilityScore(json.getAvgSubScores().incomeStability);
        overall.setAvgEmergencyCushionScore(json.getAvgSubScores().emergencyCushion);
        overall.setAvgGoalAlignmentScore(json.getAvgSubScores().goalAlignment);
        overall.setTrend(OverallProfile.Trend.valueOf(json.getTrend()));
        overall.setTrendDelta(json.getTrendDelta());
        overall.setAvgIncome(json.getAvgFinancials().avgIncome);
        overall.setAvgTotalSpend(json.getAvgFinancials().avgSpend);
        overall.setAvgActualSavings(json.getAvgFinancials().avgSavings);
        overall.setAvgLifestyleRatio(json.getAvgFinancials().avgLifestyleRatio);
        overall.setIncomeConsistency(json.getAvgFinancials().incomeConsistency);
        overall.setArchetype(json.getArchetype());
        overall.setConfirmedRecurringTotal(json.getConfirmedRecurringTotal());
        overall.setConfirmedRecurringCount(json.getConfirmedRecurringCount());
        overall.setUndeclaredRecurringCount(json.getUndeclaredRecurringCount());
        overall.setMonthlyScoreHistory(serializeJson(json.getMonthlyScores()));

        // Store full JSON
        overall.setProfileJson(serializeJson(json));
        overall.setLastRefreshedAt(LocalDateTime.now());

        overallProfileRepository.save(overall);

        log.info("OverallProfile saved for user {}: score={} trend={} months={}",
                userId, json.getAvgHealthScore(), json.getTrend(), sorted.size());

        logOverallProfile(json);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OverallProfileJson builder
    // ─────────────────────────────────────────────────────────────────────────

    private OverallProfileJson buildOverallProfileJson(
            List<StatementProfile> sorted,
            UserAssessment assessment,
            Map<String, RecurringPayment> latestConfirmed,
            List<RecurringPayment> undeclared) {

        OverallProfileJson json = new OverallProfileJson();

        // Coverage
        json.setMonthsAnalyzed(sorted.size());
        StatementProfile first = sorted.get(0);
        StatementProfile last  = sorted.get(sorted.size() - 1);
        json.setPeriod(String.format("%s – %s",
                formatMonth(first.getProfileYear(), first.getProfileMonth()),
                formatMonth(last.getProfileYear(),  last.getProfileMonth())));

        // Monthly scores list
        List<OverallProfileJson.MonthScore> monthlyScores = sorted.stream()
                .map(p -> new OverallProfileJson.MonthScore(
                        String.format("%04d-%02d", p.getProfileYear(), p.getProfileMonth()),
                        p.getHealthScore()))
                .collect(Collectors.toList());
        json.setMonthlyScores(monthlyScores);

        // Aggregate scores
        int avgHealth = avgInt(sorted, StatementProfile::getHealthScore);
        json.setAvgHealthScore(avgHealth);
        json.setLatestHealthScore(last.getHealthScore());
        json.setBestHealthScore(sorted.stream().mapToInt(StatementProfile::getHealthScore).max().orElse(0));
        json.setWorstHealthScore(sorted.stream().mapToInt(StatementProfile::getHealthScore).min().orElse(0));

        // Archetype from avg score
        int avgDiscipline = avgInt(sorted, StatementProfile::getSpendingDisciplineScore);
        int avgDebt       = avgInt(sorted, StatementProfile::getDebtBurdenScore);
        json.setArchetype(resolveArchetype(avgHealth, avgDiscipline, avgDebt));

        // Trend
        computeTrend(json, sorted);

        // Avg sub-scores
        OverallProfileJson.AvgSubScores avgSub = new OverallProfileJson.AvgSubScores();
        avgSub.savingsRate        = avgInt(sorted, StatementProfile::getSavingsRateScore);
        avgSub.spendingDiscipline = avgDiscipline;
        avgSub.debtBurden         = avgDebt;
        avgSub.incomeStability    = avgInt(sorted, StatementProfile::getIncomeStabilityScore);
        avgSub.emergencyCushion   = avgInt(sorted, StatementProfile::getEmergencyCushionScore);
        avgSub.goalAlignment      = avgInt(sorted, StatementProfile::getGoalAlignmentScore);
        json.setAvgSubScores(avgSub);

        // Avg financials
        OverallProfileJson.AvgFinancials avgFin = new OverallProfileJson.AvgFinancials();
        avgFin.avgIncome          = avgBigDecimal(sorted, StatementProfile::getActualIncome);
        avgFin.avgSpend           = avgBigDecimal(sorted, StatementProfile::getTotalSpend);
        avgFin.avgSavings         = avgBigDecimal(sorted, StatementProfile::getActualSavings);
        avgFin.avgTotalCredits    = avgBigDecimal(sorted, StatementProfile::getTotalCredits);
        avgFin.avgNetStatementFlow = avgBigDecimal(sorted, StatementProfile::getActualNetCashFlow);
        avgFin.avgLifestyleRatio = sorted.stream()
                .mapToDouble(StatementProfile::getLifestyleRatio).average().orElse(0);
        long monthsWithSalary = sorted.stream().filter(p -> p.getSalaryDay() != null).count();
        avgFin.incomeConsistency = (double) monthsWithSalary / sorted.size();
        json.setAvgFinancials(avgFin);

        // Best / worst months
        sorted.stream().max(Comparator.comparingInt(StatementProfile::getHealthScore))
                .ifPresent(p -> json.setBestMonth(
                        String.format("%04d-%02d", p.getProfileYear(), p.getProfileMonth())));
        sorted.stream().min(Comparator.comparingInt(StatementProfile::getHealthScore))
                .ifPresent(p -> json.setWorstMonth(
                        String.format("%04d-%02d", p.getProfileYear(), p.getProfileMonth())));

        // Confirmed recurring
        List<OverallProfileJson.ConfirmedRecurring> confirmedList = latestConfirmed.values().stream()
                .sorted(Comparator.comparing(RecurringPayment::getAmount).reversed())
                .map(r -> new OverallProfileJson.ConfirmedRecurring(
                        r.getMerchant(), r.getAmount(),
                        r.getRecurringType().name(),
                        r.getMonthsDetected(),
                        r.isDeclaredInAssessment()))
                .collect(Collectors.toList());
        json.setConfirmedRecurring(confirmedList);

        BigDecimal recurringTotal = latestConfirmed.values().stream()
                .map(RecurringPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        json.setConfirmedRecurringTotal(recurringTotal);
        json.setConfirmedRecurringCount(latestConfirmed.size());
        json.setUndeclaredRecurringCount(
                (int) undeclared.stream()
                        .map(RecurringPayment::getMerchantKey)
                        .distinct().count());

        // Category spending trend
        json.setSpendingTrendByCategory(computeCategoryTrend(sorted));

        // Sub-score improvements and weaknesses
        json.setBiggestImprovements(computeImprovements(sorted));
        json.setBiggestWeaknesses(computeWeaknesses(avgSub));

        // Overall strengths, risks, actions
        json.setOverallStrengths(buildOverallStrengths(avgSub, avgFin, json, assessment, latestConfirmed));
        json.setOverallRisks(buildOverallRisks(avgSub, avgFin, json, sorted));
        json.setOverallActions(buildOverallActions(avgSub, assessment, latestConfirmed, sorted, avgFin));

        return json;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Category spending trend
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, List<BigDecimal>> computeCategoryTrend(List<StatementProfile> sorted) {
        // Parse profileJson for each month to extract spendingBreakdown
        Map<String, List<BigDecimal>> trend = new LinkedHashMap<>();

        for (StatementProfile p : sorted) {
            if (p.getProfileJson() == null) continue;
            try {
                MonthlyProfileJson monthly = objectMapper.readValue(
                        p.getProfileJson(), MonthlyProfileJson.class);
                if (monthly.getSpendingBreakdown() == null) continue;

                monthly.getSpendingBreakdown().forEach((cat, amt) -> {
                    trend.computeIfAbsent(cat, k -> new ArrayList<>()).add(amt);
                });
            } catch (Exception e) {
                log.warn("Failed to parse profile JSON for month {}-{}: {}",
                        p.getProfileYear(), p.getProfileMonth(), e.getMessage());
            }
        }

        // Only keep top 6 categories by total spend across all months
        return trend.entrySet().stream()
                .sorted((a, b) -> {
                    BigDecimal sumA = a.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal sumB = b.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    return sumB.compareTo(sumA);
                })
                .limit(6)
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (x, y) -> x, LinkedHashMap::new));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sub-score improvements and weaknesses
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> computeImprovements(List<StatementProfile> sorted) {
        if (sorted.size() < 2) return Collections.emptyList();
        StatementProfile first = sorted.get(0);
        StatementProfile last  = sorted.get(sorted.size() - 1);

        Map<String, Integer> deltas = new LinkedHashMap<>();
        deltas.put("Savings Rate",        last.getSavingsRateScore()        - first.getSavingsRateScore());
        deltas.put("Spending Discipline", last.getSpendingDisciplineScore() - first.getSpendingDisciplineScore());
        deltas.put("Debt Burden",         last.getDebtBurdenScore()         - first.getDebtBurdenScore());
        deltas.put("Income Stability",    last.getIncomeStabilityScore()    - first.getIncomeStabilityScore());
        deltas.put("Emergency Cushion",   last.getEmergencyCushionScore()   - first.getEmergencyCushionScore());
        deltas.put("Goal Alignment",      last.getGoalAlignmentScore()      - first.getGoalAlignmentScore());

        return deltas.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(e -> String.format("%s (+%d pts)", e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private List<String> computeWeaknesses(OverallProfileJson.AvgSubScores avg) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("Savings Rate",        avg.savingsRate);
        scores.put("Spending Discipline", avg.spendingDiscipline);
        scores.put("Debt Burden",         avg.debtBurden);
        scores.put("Income Stability",    avg.incomeStability);
        scores.put("Emergency Cushion",   avg.emergencyCushion);
        scores.put("Goal Alignment",      avg.goalAlignment);

        return scores.entrySet().stream()
                .filter(e -> e.getValue() < 60)
                .sorted(Map.Entry.comparingByValue())
                .limit(3)
                .map(e -> String.format("%s (avg %d/100)", e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overall strengths / risks / actions
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> buildOverallStrengths(OverallProfileJson.AvgSubScores sub,
                                               OverallProfileJson.AvgFinancials fin,
                                               OverallProfileJson json,
                                               UserAssessment assessment,
                                               Map<String, RecurringPayment> confirmed) {
        List<String> list = new ArrayList<>();

        if (sub.incomeStability >= 75 && fin.incomeConsistency >= 0.9)
            list.add(String.format("Salary credited consistently in %.0f%% of months — very stable income",
                    fin.incomeConsistency * 100));

        if (json.getTrendDelta() >= 5)
            list.add(String.format("Health score improved %+d points over the analysis period — positive trajectory",
                    json.getTrendDelta()));

        if (sub.savingsRate >= 70)
            list.add(String.format("Strong savings rate — averaging ₹%s saved per month",
                    fmt(fin.avgSavings)));

        if (sub.debtBurden >= 80)
            list.add("Low debt burden — healthy financial flexibility maintained across months");

        if (assessment != null && Boolean.FALSE.equals(assessment.getHasDebt()))
            list.add("Debt-free — full income available for wealth building");

        // Compare declared vs actual savings
        if (assessment != null && assessment.getMonthlySavings() != null && fin.avgSavings != null
                && fin.avgSavings.compareTo(assessment.getMonthlySavings()) > 0) {
            BigDecimal ratio = fin.avgSavings.divide(assessment.getMonthlySavings(), 1, RoundingMode.HALF_UP);
            list.add(String.format(
                    "Consistently saving %.1fx more than declared — avg ₹%s vs declared ₹%s/month",
                    ratio.doubleValue(), fmt(fin.avgSavings), fmt(assessment.getMonthlySavings())));
        }

        if (list.isEmpty())
            list.add("Taking proactive steps to understand and improve your financial health");

        return list;
    }

    private List<String> buildOverallRisks(OverallProfileJson.AvgSubScores sub,
                                           OverallProfileJson.AvgFinancials fin,
                                           OverallProfileJson json,
                                           List<StatementProfile> sorted) {
        List<String> list = new ArrayList<>();

        if (sub.emergencyCushion <= 20)
            list.add("No emergency fund detected across all months — critical vulnerability");
        else if (sub.emergencyCushion <= 40)
            list.add("Insufficient emergency fund — less than 2 months coverage");

        if (sub.debtBurden <= 40)
            list.add("High debt burden consistently — significant fixed obligations limiting wealth building");

        if (sub.savingsRate <= 30)
            list.add("Very low savings rate across all months — vulnerable to any financial shock");

        if (fin.avgLifestyleRatio > 45)
            list.add(String.format(
                    "%.0f%% of average monthly spend is discretionary — significant optimisation potential",
                    fin.avgLifestyleRatio));

        // Post-salary surge consistency
        long surgeMonths = sorted.stream().filter(StatementProfile::isPostSalarySurge).count();
        if (surgeMonths >= 3)
            list.add(String.format(
                    "Post-salary spending surge in %d of %d months — consistent behavioural pattern",
                    surgeMonths, sorted.size()));

        if (json.getTrendDelta() <= -5)
            list.add(String.format("Score declining — down %d points over last 3 months", Math.abs(json.getTrendDelta())));

        return list;
    }

    private List<String> buildOverallActions(OverallProfileJson.AvgSubScores sub,
                                             UserAssessment assessment,
                                             Map<String, RecurringPayment> confirmed,
                                             List<StatementProfile> sorted,
                                             OverallProfileJson.AvgFinancials fin) {
        List<String> list = new ArrayList<>();

        if (sub.emergencyCushion <= 40 && assessment != null && fin.avgSpend != null) {
            BigDecimal target = fin.avgSpend.multiply(BigDecimal.valueOf(6));
            list.add(String.format(
                    "Emergency fund is #1 priority — open a liquid FD for ₹%s (6 months avg spend)",
                    fmt(target)));
        }

        if (sub.savingsRate < 60 && assessment != null && assessment.getMonthlyIncome() != null) {
            BigDecimal target20 = assessment.getMonthlyIncome().multiply(BigDecimal.valueOf(0.20));
            list.add(String.format(
                    "Automate ₹%s/month to savings on salary day — 20%% rule",
                    fmt(target20)));
        }

        if (sub.debtBurden < 50)
            list.add("Prioritise highest-interest debt first using avalanche method");

        // Post-salary surge fix
        long surgeMonths = sorted.stream().filter(StatementProfile::isPostSalarySurge).count();
        if (surgeMonths >= 3)
            list.add("48-hour rule — no large purchases for 2 days after every salary credit");

        // Confirmed recurring that's large EMI
        confirmed.values().stream()
                .filter(r -> r.getRecurringType() == RecurringPayment.RecurringType.EMI)
                .max(Comparator.comparing(RecurringPayment::getAmount))
                .ifPresent(emi -> list.add(String.format(
                        "Track %s payoff date actively — clearing ₹%s/month EMI will significantly boost score",
                        emi.getMerchant(), fmt(emi.getAmount()))));

        if (assessment != null && assessment.getGoalDeadline() != null && sub.goalAlignment < 60)
            list.add("Review goal timeline or increase monthly savings — current pace won't reach target by deadline");

        if (list.isEmpty())
            list.add("Step up SIP contributions by 10% annually — solid foundation to build on");

        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recurring confidence upgrade
    // ─────────────────────────────────────────────────────────────────────────

    private void upgradeRecurringConfidence(Long userId) {
        List<String> keys = recurringPaymentRepository.findDistinctMerchantKeysByUserId(userId);
        for (String key : keys) {
            long count = recurringPaymentRepository.countDistinctMonthsByMerchantKey(userId, key);
            RecurringPayment.Confidence confidence =
                    count >= CONFIRMED_MONTHS ? RecurringPayment.Confidence.CONFIRMED
                            : count >= LIKELY_MONTHS  ? RecurringPayment.Confidence.LIKELY
                            :                           RecurringPayment.Confidence.POSSIBLE;

            List<RecurringPayment> rows = recurringPaymentRepository
                    .findByUserIdOrderByProfileYearDescProfileMonthDesc(userId)
                    .stream().filter(r -> r.getMerchantKey().equals(key))
                    .collect(Collectors.toList());

            rows.forEach(r -> { r.setConfidence(confidence); r.setMonthsDetected((int) count); });
            recurringPaymentRepository.saveAll(rows);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trend
    // ─────────────────────────────────────────────────────────────────────────

    private void computeTrend(OverallProfileJson json, List<StatementProfile> sorted) {
        if (sorted.size() < 2) {
            json.setTrend("INSUFFICIENT_DATA");
            json.setTrendDelta(0);
            return;
        }
        int window = Math.min(3, sorted.size());
        int oldest = sorted.get(sorted.size() - window).getHealthScore();
        int newest = sorted.get(sorted.size() - 1).getHealthScore();
        int delta  = newest - oldest;
        json.setTrendDelta(delta);
        if      (delta >=  TREND_THRESHOLD) json.setTrend("IMPROVING");
        else if (delta <= -TREND_THRESHOLD) json.setTrend("DECLINING");
        else                                json.setTrend("STABLE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, RecurringPayment> deduplicateByKey(List<RecurringPayment> list) {
        Map<String, RecurringPayment> map = new LinkedHashMap<>();
        list.stream()
                .sorted(Comparator.comparingInt(RecurringPayment::getProfileYear)
                        .thenComparingInt(RecurringPayment::getProfileMonth).reversed())
                .forEach(r -> map.putIfAbsent(r.getMerchantKey(), r));
        return map;
    }

    private int avgInt(List<StatementProfile> list,
                       java.util.function.ToIntFunction<StatementProfile> fn) {
        return (int) Math.round(list.stream().mapToInt(fn).average().orElse(0));
    }

    private BigDecimal avgBigDecimal(List<StatementProfile> list,
                                     java.util.function.Function<StatementProfile, BigDecimal> fn) {
        List<BigDecimal> nonNull = list.stream().map(fn).filter(Objects::nonNull).collect(Collectors.toList());
        if (nonNull.isEmpty()) return BigDecimal.ZERO;
        return nonNull.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(nonNull.size()), 2, RoundingMode.HALF_UP);
    }

    private String resolveArchetype(int score, int discipline, int debt) {
        if (score >= 90) return "The Wealth Builder";
        if (score >= 75) return "The Disciplined Saver";
        if (score >= 60) return discipline < 50 ? "The Lifestyle Optimizer" : "The Balanced Spender";
        if (score >= 45) return debt < 50 ? "The Debt Wrestler" : "The Aspirational Improver";
        if (score >= 30) return "The Cash-Flow Juggler";
        return "The Financial Rebuilder";
    }

    private String formatMonth(int year, int month) {
        return YearMonth.of(year, month)
                .format(DateTimeFormatter.ofPattern("MMM yyyy"));
    }

    private String serializeJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { log.error("JSON serialize error: {}", e.getMessage()); return "{}"; }
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal log
    // ─────────────────────────────────────────────────────────────────────────

    private void logOverallProfile(OverallProfileJson p) {
        String bar = "═".repeat(65);
        String thin = "─".repeat(65);
        StringBuilder sb = new StringBuilder("\n");
        sb.append(bar).append("\n");
        sb.append("  🏦 OVERALL PROFILE REFRESHED\n");
        sb.append(bar).append("\n");
        sb.append(String.format("  Archetype : %s%n", p.getArchetype()));
        sb.append(String.format("  Period    : %s (%d months)%n", p.getPeriod(), p.getMonthsAnalyzed()));
        sb.append(thin).append("\n");

        int score = p.getAvgHealthScore();
        sb.append(String.format("  AVG SCORE : %d/100  [%s]  %s%n",
                score, "█".repeat(score/5) + "░".repeat(20-score/5),
                score >= 75 ? "GOOD" : score >= 50 ? "FAIR" : "POOR"));
        sb.append(String.format("  LATEST    : %d  BEST: %d  WORST: %d%n",
                p.getLatestHealthScore(), p.getBestHealthScore(), p.getWorstHealthScore()));
        sb.append(String.format("  TREND     : %s (%+d pts)%n", p.getTrend(), p.getTrendDelta()));
        sb.append(thin).append("\n");

        if (p.getAvgSubScores() != null) {
            OverallProfileJson.AvgSubScores s = p.getAvgSubScores();
            sb.append("  AVG SUB-SCORES\n");
            sb.append(String.format("  %-28s %3d/100%n", "Savings Rate",        s.savingsRate));
            sb.append(String.format("  %-28s %3d/100%n", "Spending Discipline", s.spendingDiscipline));
            sb.append(String.format("  %-28s %3d/100%n", "Debt Burden",         s.debtBurden));
            sb.append(String.format("  %-28s %3d/100%n", "Income Stability",    s.incomeStability));
            sb.append(String.format("  %-28s %3d/100%n", "Emergency Cushion",   s.emergencyCushion));
            sb.append(String.format("  %-28s %3d/100%n", "Goal Alignment",      s.goalAlignment));
            sb.append(thin).append("\n");
        }

        if (p.getAvgFinancials() != null) {
            OverallProfileJson.AvgFinancials f = p.getAvgFinancials();
            sb.append("  AVG FINANCIALS\n");
            sb.append(String.format("  Income    : ₹%s/month%n", fmt(f.avgIncome)));
            sb.append(String.format("  Spend     : ₹%s/month%n", fmt(f.avgSpend)));
            sb.append(String.format("  Savings   : ₹%s/month%n", fmt(f.avgSavings)));
            sb.append(String.format("  Lifestyle : %.1f%%%n", f.avgLifestyleRatio));
            sb.append(String.format("  Income consistency: %.0f%%%n", f.incomeConsistency * 100));
            sb.append(thin).append("\n");
        }

        if (p.getConfirmedRecurring() != null && !p.getConfirmedRecurring().isEmpty()) {
            sb.append("  CONFIRMED RECURRING\n");
            p.getConfirmedRecurring().forEach(r -> sb.append(String.format(
                    "  %-30s ₹%s  %d months%s%n",
                    r.merchant, fmt(r.amount), r.monthsDetected,
                    r.declaredInAssessment ? "" : "  ⚠ UNDECLARED")));
            sb.append(String.format("  Total: ₹%s/month%n", fmt(p.getConfirmedRecurringTotal())));
            sb.append(thin).append("\n");
        }

        if (p.getBiggestImprovements() != null && !p.getBiggestImprovements().isEmpty()) {
            sb.append("  BIGGEST IMPROVEMENTS: ").append(String.join(", ", p.getBiggestImprovements())).append("\n");
        }
        if (p.getBiggestWeaknesses() != null && !p.getBiggestWeaknesses().isEmpty()) {
            sb.append("  PERSISTENT WEAKNESSES: ").append(String.join(", ", p.getBiggestWeaknesses())).append("\n");
            sb.append(thin).append("\n");
        }

        if (p.getOverallStrengths() != null) {
            sb.append("  OVERALL STRENGTHS\n");
            p.getOverallStrengths().forEach(s -> sb.append("  ✓ ").append(s).append("\n"));
            sb.append("\n");
        }
        if (p.getOverallRisks() != null) {
            sb.append("  OVERALL RISKS\n");
            p.getOverallRisks().forEach(r -> sb.append("  ✗ ").append(r).append("\n"));
            sb.append("\n");
        }
        if (p.getOverallActions() != null) {
            sb.append("  OVERALL ACTIONS\n");
            for (int i = 0; i < p.getOverallActions().size(); i++)
                sb.append(String.format("  %d. %s%n", i+1, p.getOverallActions().get(i)));
        }

        sb.append(bar).append("\n");
        log.info(sb.toString());
    }
}