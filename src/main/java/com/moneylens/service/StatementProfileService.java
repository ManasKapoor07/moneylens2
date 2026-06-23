package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.profile.MonthlyProfileJson;
import com.moneylens.entity.*;
import com.moneylens.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.moneylens.repository.RecurringPaymentRepository;
import com.moneylens.repository.StatementProfileRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StatementProfileService {

    private static final Logger log = LoggerFactory.getLogger(StatementProfileService.class);

    // Spending Behavior Score weights — all sub-scores are statement-only
    private static final double W_DISCIPLINE  = 0.35;
    private static final double W_DEBT_SVC    = 0.25;
    private static final double W_VELOCITY    = 0.20;
    private static final double W_RECURRING   = 0.20;

    private static final Set<String> LIFESTYLE_CATS = Set.of(
            "food", "dining", "entertainment", "travel", "shopping",
            "drinks", "restaurants", "subscriptions", "fashion"
    );

    private final StatementProfileRepository  profileRepository;
    private final RecurringPaymentRepository  recurringRepository;
    private final RecurringExpenseDetector    recurringDetector;
    private final SalaryRunwayAnalyzer        salaryRunwayAnalyzer;
    private final WeeklySpendingVelocityAnalyzer weeklyAnalyzer;
    private final ObjectMapper                objectMapper;
    private OverallProfileService             overallProfileService;

    public StatementProfileService(
            StatementProfileRepository profileRepository,
            RecurringPaymentRepository recurringRepository,
            RecurringExpenseDetector recurringDetector,
            SalaryRunwayAnalyzer salaryRunwayAnalyzer,
            WeeklySpendingVelocityAnalyzer weeklyAnalyzer,
            OverallProfileService overallProfileService,
            ObjectMapper objectMapper) {

        this.profileRepository    = profileRepository;
        this.recurringRepository  = recurringRepository;
        this.recurringDetector    = recurringDetector;
        this.salaryRunwayAnalyzer = salaryRunwayAnalyzer;
        this.weeklyAnalyzer       = weeklyAnalyzer;
        this.objectMapper         = objectMapper;
        this.overallProfileService = overallProfileService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public int buildAndSaveAll(BankStatement statement, User user, List<Transaction> transactions) {
        return buildAndSaveAll(statement, user, transactions, false);
    }

    /**
     * @param forceRecompute  When true, deletes and rebuilds any existing
     *                        StatementProfile for a month instead of skipping it.
     */
    public int buildAndSaveAll(BankStatement statement, User user, List<Transaction> transactions,
                               boolean forceRecompute) {
        Map<YearMonth, List<Transaction>> byMonth = transactions.stream()
                .filter(tx -> tx.getStatementYear() > 0 && tx.getStatementMonth() > 0)
                .collect(Collectors.groupingBy(tx ->
                        YearMonth.of(tx.getStatementYear(), tx.getStatementMonth())));

        List<YearMonth> months = new ArrayList<>(byMonth.keySet());
        Collections.sort(months);

        log.info("Building profiles for {} month(s) for user {} (forceRecompute={})",
                months.size(), user.getId(), forceRecompute);

        int created = 0;
        for (YearMonth month : months) {
            boolean exists = profileRepository.existsByUserIdAndProfileYearAndProfileMonth(
                    user.getId(), month.getYear(), month.getMonthValue());

            if (exists && !forceRecompute) {
                log.info("Profile exists for user={} month={} — skipping", user.getId(), month);
                continue;
            }

            if (exists && forceRecompute) {
                profileRepository.findByUserIdAndProfileYearAndProfileMonth(
                                user.getId(), month.getYear(), month.getMonthValue())
                        .ifPresent(old -> {
                            recurringRepository.deleteByStatementProfileId(old.getId());
                            profileRepository.delete(old);
                        });
                log.info("Deleted stale profile for user={} month={} — rebuilding", user.getId(), month);
            }

            buildAndSaveForMonth(statement, user, month, byMonth.get(month));
            created++;
        }

        log.info("Created/refreshed {} StatementProfile(s) for user {}", created, user.getId());
        if (created > 0) {
            overallProfileService.refresh(user.getId());
        }
        return created;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-month build
    // ─────────────────────────────────────────────────────────────────────────

    private void buildAndSaveForMonth(BankStatement statement, User user,
                                      YearMonth month, List<Transaction> monthTx) {

        log.info("Building profile for user={} month={} tx={}", user.getId(), month, monthTx.size());

        // ── 1. Financials (pure statement math) ────────────────────────────
        BigDecimal totalCredits  = sumCredits(monthTx);
        BigDecimal totalDebits   = sumDebits(monthTx);
        BigDecimal netFlow       = totalCredits.subtract(totalDebits);
        BigDecimal avgDailySpend = totalDebits.divide(
                BigDecimal.valueOf(month.lengthOfMonth()), 2, RoundingMode.HALF_UP);
        double lifestyleRatio    = computeLifestyleRatio(monthTx, totalDebits);

        // ── 2. Tier-1 analyzers ────────────────────────────────────────────
        List<RecurringExpenseDetector.RecurringExpense> detected =
                recurringDetector.detect(monthTx);
        SalaryRunwayAnalyzer.RunwayResult runway = salaryRunwayAnalyzer.analyze(monthTx);
        WeeklySpendingVelocityAnalyzer.VelocityResult velocity = weeklyAnalyzer.analyze(monthTx);

        // ── 3. Spending Behavior Score (4 statement-only sub-scores) ───────
        int disciplineScore  = scoreSpendingDiscipline(monthTx);
        int debtSvcScore     = scoreDebtServiceLoad(monthTx, totalDebits);
        int velocityScore    = scoreVelocityConsistency(velocity, runway);
        int recurringScore   = scoreRecurringObligationLoad(detected, totalDebits);
        int behaviorScore    = weightedScore(disciplineScore, debtSvcScore, velocityScore, recurringScore);
        String archetype     = resolveArchetype(behaviorScore, disciplineScore);

        // ── 4. Spending breakdown by category ──────────────────────────────
        Map<String, BigDecimal> spendingBreakdown = computeSpendingBreakdown(monthTx);

        // ── 5. Top merchants ───────────────────────────────────────────────
        List<MonthlyProfileJson.MerchantEntry> topMerchants = computeTopMerchants(monthTx);

        // ── 6. Strengths / risks / actions ────────────────────────────────
        SubScoreSet sub = new SubScoreSet(disciplineScore, debtSvcScore, velocityScore, recurringScore);
        List<String> strengths = buildStrengths(sub, lifestyleRatio, runway, velocity);
        List<String> risks     = buildRisks(sub, lifestyleRatio, runway, velocity);
        List<String> actions   = buildActions(sub, runway, velocity, topMerchants);

        // ── 7. Save StatementProfile entity ───────────────────────────────
        StatementProfile profile = new StatementProfile();
        profile.setUser(user);
        profile.setStatement(statement);
        profile.setProfileYear(month.getYear());
        profile.setProfileMonth(month.getMonthValue());
        profile.setHealthScore(behaviorScore);
        profile.setArchetype(archetype);
        profile.setSpendingDisciplineScore(disciplineScore);
        profile.setDebtBurdenScore(debtSvcScore);
        profile.setIncomeStabilityScore(velocityScore);
        profile.setEmergencyCushionScore(recurringScore);
        profile.setTotalCredits(totalCredits);
        profile.setTotalSpend(totalDebits);
        profile.setActualNetCashFlow(netFlow);
        profile.setAvgDailySpend(avgDailySpend);
        profile.setLifestyleRatio(lifestyleRatio);
        profile.setTransactionCount(monthTx.size());

        if (runway.isAvailable()) {
            profile.setSalaryDay(runway.salaryDayOfMonth);
            profile.setRunwayDays(runway.runwayDays);
            profile.setPostSalarySurge(runway.postSalarySurge);
        }
        if (velocity != null) {
            profile.setWeeklyPattern(velocity.pattern);
            profile.setWeek1Spend(velocity.week1);
            profile.setWeek2Spend(velocity.week2);
            profile.setWeek3Spend(velocity.week3);
            profile.setWeek4Spend(velocity.week4);
        }

        // ── 8. Build MonthlyProfileJson and serialize ──────────────────────
        MonthlyProfileJson json = buildMonthlyProfileJson(
                month, behaviorScore, archetype, monthTx.size(),
                sub, totalCredits, totalDebits, netFlow, avgDailySpend, lifestyleRatio,
                spendingBreakdown, topMerchants, runway, velocity, detected,
                strengths, risks, actions);

        profile.setProfileJson(serializeJson(json));

        StatementProfile saved = profileRepository.save(profile);

        // ── 9. Save RecurringPayment rows ──────────────────────────────────
        saveRecurringPayments(detected, saved, user, month);

        // ── 10. Terminal log ───────────────────────────────────────────────
        logProfile(json);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MonthlyProfileJson builder
    // ─────────────────────────────────────────────────────────────────────────

    private MonthlyProfileJson buildMonthlyProfileJson(
            YearMonth month, int behaviorScore, String archetype, int txCount,
            SubScoreSet sub,
            BigDecimal totalCredits, BigDecimal totalDebits, BigDecimal netFlow,
            BigDecimal avgDailySpend, double lifestyleRatio,
            Map<String, BigDecimal> spendingBreakdown,
            List<MonthlyProfileJson.MerchantEntry> topMerchants,
            SalaryRunwayAnalyzer.RunwayResult runway,
            WeeklySpendingVelocityAnalyzer.VelocityResult velocity,
            List<RecurringExpenseDetector.RecurringExpense> detected,
            List<String> strengths, List<String> risks, List<String> actions) {

        MonthlyProfileJson json = new MonthlyProfileJson();
        json.setMonth(String.format("%04d-%02d", month.getYear(), month.getMonthValue()));
        json.setHealthScore(behaviorScore);
        json.setArchetype(archetype);
        json.setTransactionCount(txCount);

        MonthlyProfileJson.SubScores ss = new MonthlyProfileJson.SubScores();
        ss.spendingDiscipline      = sub.discipline;
        ss.debtServiceLoad         = sub.debtSvc;
        ss.velocityConsistency     = sub.velocity;
        ss.recurringObligationLoad = sub.recurring;
        json.setSubScores(ss);

        MonthlyProfileJson.Financials fin = new MonthlyProfileJson.Financials();
        fin.totalCredits      = totalCredits;
        fin.totalDebits       = totalDebits;
        fin.netStatementFlow  = netFlow;
        fin.avgDailySpend     = avgDailySpend;
        fin.lifestyleRatio    = lifestyleRatio;
        json.setFinancials(fin);

        json.setSpendingBreakdown(spendingBreakdown);
        json.setTopMerchants(topMerchants);

        MonthlyProfileJson.SalaryRunway sr = new MonthlyProfileJson.SalaryRunway();
        sr.salaryDay       = runway.salaryDayOfMonth;
        sr.runwayDays      = runway.runwayDays;
        sr.postSalarySurge = runway.postSalarySurge;
        sr.insight         = runway.insight;
        json.setSalaryRunway(sr);

        if (velocity != null) {
            MonthlyProfileJson.WeeklyPattern wp = new MonthlyProfileJson.WeeklyPattern();
            wp.pattern           = velocity.pattern;
            wp.week1             = velocity.week1;
            wp.week2             = velocity.week2;
            wp.week3             = velocity.week3;
            wp.week4             = velocity.week4;
            wp.week1ToWeek4Ratio = velocity.week1ToWeek4Ratio;
            wp.insight           = velocity.insight;
            json.setWeeklyPattern(wp);
        }

        List<MonthlyProfileJson.RecurringEntry> recurringList = detected.stream().map(r -> {
            MonthlyProfileJson.RecurringEntry e = new MonthlyProfileJson.RecurringEntry();
            e.merchant       = r.merchant;
            e.amount         = r.totalAmount;
            e.type           = r.type != null ? r.type.name() : "REPEATED_DEBIT";
            e.confidence     = "POSSIBLE";
            e.monthsDetected = 1;
            return e;
        }).collect(Collectors.toList());
        json.setRecurringPayments(recurringList);

        json.setStrengths(strengths);
        json.setRisks(risks);
        json.setRecommendedActions(actions);

        return json;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spending breakdown
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, BigDecimal> computeSpendingBreakdown(List<Transaction> monthTx) {
        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();

        monthTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .forEach(t -> {
                    String cat = resolveCategory(t);
                    BigDecimal amt = t.getWithdrawalAmount() != null
                            ? t.getWithdrawalAmount() : BigDecimal.ZERO;
                    breakdown.merge(cat, amt, BigDecimal::add);
                });

        return breakdown.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private String resolveCategory(Transaction t) {
        Category effective = t.getEffectiveCategory();
        if (effective != null) return effective.getDisplayName();
        if (t.getCategory() != null && !t.getCategory().isBlank()) return t.getCategory();
        return Category.OTHER.getDisplayName();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top merchants
    // ─────────────────────────────────────────────────────────────────────────

    private List<MonthlyProfileJson.MerchantEntry> computeTopMerchants(List<Transaction> monthTx) {
        Map<String, BigDecimal> merchantTotals = new LinkedHashMap<>();

        monthTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> t.getMerchantName() != null || t.getCounterpartyName() != null)
                .forEach(t -> {
                    String name = t.getMerchantName() != null
                            ? t.getMerchantName() : t.getCounterpartyName();
                    BigDecimal amt = t.getWithdrawalAmount() != null
                            ? t.getWithdrawalAmount() : BigDecimal.ZERO;
                    merchantTotals.merge(name, amt, BigDecimal::add);
                });

        return merchantTotals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(5)
                .map(e -> new MonthlyProfileJson.MerchantEntry(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strengths / Risks / Actions — statement-only, no assessment references
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> buildStrengths(SubScoreSet sub, double lifestyleRatio,
                                        SalaryRunwayAnalyzer.RunwayResult runway,
                                        WeeklySpendingVelocityAnalyzer.VelocityResult velocity) {
        List<String> list = new ArrayList<>();

        if (sub.discipline >= 75)
            list.add("Controlled discretionary spending — your lifestyle ratio is healthy at "
                    + String.format("%.0f%%", lifestyleRatio));
        if (sub.debtSvc >= 80)
            list.add("Debt-free or near debt-free — low EMI load means most of your income stays flexible");
        if (sub.velocity >= 70 && velocity != null && "STEADY".equals(velocity.pattern))
            list.add("Steady spending rhythm — your spend is evenly distributed across all four weeks, a hallmark of disciplined budgeters");
        if (sub.recurring >= 80)
            list.add("Low fixed commitment load — you have plenty of breathing room in monthly cash flow");
        if (runway.isAvailable() && runway.salaryDayOfMonth != null && !runway.postSalarySurge)
            list.add(String.format("No post-salary splurge detected — salary arrives on day %d and you pace spending well",
                    runway.salaryDayOfMonth));
        if (runway.isAvailable() && runway.runwayDays != null && runway.runwayDays >= 20)
            list.add(String.format("Strong money runway — you sustain spending for %d days on salary credit alone",
                    runway.runwayDays));

        if (list.isEmpty())
            list.add("You're actively tracking your spending — financial awareness is the foundation of every healthy financial life");

        return list;
    }

    private List<String> buildRisks(SubScoreSet sub, double lifestyleRatio,
                                    SalaryRunwayAnalyzer.RunwayResult runway,
                                    WeeklySpendingVelocityAnalyzer.VelocityResult velocity) {
        List<String> list = new ArrayList<>();

        if (sub.discipline <= 40)
            list.add(String.format(
                    "High lifestyle spend — %.0f%% of debits are discretionary. Consider shifting 10%% toward savings",
                    lifestyleRatio));
        else if (lifestyleRatio > 60)
            list.add(String.format(
                    "%.0f%% of spend is discretionary — there is significant room to optimise without impacting essentials",
                    lifestyleRatio));

        if (sub.debtSvc <= 40)
            list.add("Heavy debt-service burden — EMI/loan payments are consuming a large share of monthly outflow");

        if (sub.recurring <= 40)
            list.add("High fixed recurring obligations detected — this limits your ability to flex spending in lean months");

        if (runway.isAvailable() && runway.postSalarySurge)
            list.add("Post-salary spending surge detected — a significant share of your monthly spend happens within 3 days of salary credit");

        if (runway.isAvailable() && runway.runwayDays != null && runway.runwayDays < 10)
            list.add(String.format(
                    "Very short money runway — 60%% of monthly spend consumed within just %d day(s) of salary. Cash flow risk is elevated",
                    runway.runwayDays));

        if (velocity != null && "FRONT_HEAVY".equals(velocity.pattern))
            list.add(String.format(
                    "Front-heavy spending — ₹%s in week 1 vs ₹%s in week 4. This can leave you cash-strapped before the next salary",
                    fmt(velocity.week1), fmt(velocity.week4)));

        if (velocity != null && "BACK_HEAVY".equals(velocity.pattern))
            list.add(String.format(
                    "Back-heavy spending — ₹%s in week 4 vs ₹%s in week 1. Large end-of-month payments (rent, bills) may be driving this",
                    fmt(velocity.week4), fmt(velocity.week1)));

        return list;
    }

    private List<String> buildActions(SubScoreSet sub,
                                      SalaryRunwayAnalyzer.RunwayResult runway,
                                      WeeklySpendingVelocityAnalyzer.VelocityResult velocity,
                                      List<MonthlyProfileJson.MerchantEntry> topMerchants) {
        List<String> list = new ArrayList<>();

        // Top merchant — most impactful specific insight
        if (!topMerchants.isEmpty()) {
            MonthlyProfileJson.MerchantEntry top = topMerchants.get(0);
            String name = top.name.substring(0, 1).toUpperCase() + top.name.substring(1).toLowerCase();
            list.add(String.format(
                    "Your biggest single merchant is %s at ₹%s this month — set a monthly cap and check if every transaction was intentional",
                    name, fmt(top.amount)));
        }

        // Post-salary surge
        if (runway.isAvailable() && runway.postSalarySurge)
            list.add("Apply the 48-hour rule — delay any non-essential purchase for 48 hours after salary credit to avoid impulse spending");

        // Weekly pattern actions
        if (velocity != null && "FRONT_HEAVY".equals(velocity.pattern))
            list.add(String.format(
                    "Set a weekly allowance of ₹%s — you spent ₹%s in week 1 alone. Splitting the budget weekly prevents early-month blowouts",
                    fmt(velocity.week1.add(velocity.week4).divide(java.math.BigDecimal.valueOf(2), 0, java.math.RoundingMode.HALF_UP)),
                    fmt(velocity.week1)));

        if (velocity != null && "BACK_HEAVY".equals(velocity.pattern))
            list.add("Move large fixed payments (rent, bills) to an auto-debit on salary day so they don't feel like a week-4 surprise");

        // Debt
        if (sub.debtSvc < 50)
            list.add("Use the avalanche method — list all loans by interest rate and direct any surplus toward the highest-rate one first");

        // Discipline
        if (sub.discipline < 60)
            list.add("Try the 50/30/20 framework — 50% on needs, 30% on wants, 20% saved. Even a rough target beats no target");

        // Recurring
        if (sub.recurring < 50)
            list.add("Audit your recurring charges — cancel or pause any subscription you haven't actively used in the last 30 days");

        // Runway-based saving tip
        if (runway.isAvailable() && runway.salaryDayOfMonth != null && !runway.postSalarySurge)
            list.add(String.format(
                    "Great runway control — consider automating a SIP or RD transfer on day %d (salary day) before discretionary spend begins",
                    runway.salaryDayOfMonth));

        // Second merchant if there are multiple
        if (topMerchants.size() >= 2) {
            MonthlyProfileJson.MerchantEntry second = topMerchants.get(1);
            String name2 = second.name.substring(0, 1).toUpperCase() + second.name.substring(1).toLowerCase();
            list.add(String.format(
                    "%s is your #2 spend at ₹%s — if this is a want category, even a 20%% reduction saves ₹%s/month",
                    name2, fmt(second.amount),
                    fmt(second.amount.multiply(java.math.BigDecimal.valueOf(0.20)))));
        }

        if (list.isEmpty())
            list.add("Spending patterns look healthy — consider stepping up your SIP by 10%% to accelerate goal achievement");

        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recurring payment persistence
    // ─────────────────────────────────────────────────────────────────────────

    private void saveRecurringPayments(List<RecurringExpenseDetector.RecurringExpense> detected,
                                       StatementProfile saved, User user, YearMonth month) {
        List<RecurringPayment> rows = detected.stream().map(expense -> {
            RecurringPayment rp = new RecurringPayment();
            rp.setUser(user);
            rp.setStatementProfile(saved);
            rp.setProfileYear(month.getYear());
            rp.setProfileMonth(month.getMonthValue());
            rp.setMerchant(expense.merchant);
            rp.setMerchantKey(expense.merchant.toLowerCase().trim());
            rp.setAmount(expense.totalAmount);
            rp.setOccurrences(expense.occurrences > 0 ? expense.occurrences : 1);
            rp.setRecurringType(mapType(expense.type));
            rp.setConfidence(RecurringPayment.Confidence.POSSIBLE);
            return rp;
        }).collect(Collectors.toList());

        recurringRepository.saveAll(rows);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spending Behavior Score — 4 statement-only sub-scores
    // ─────────────────────────────────────────────────────────────────────────

    private int scoreSpendingDiscipline(List<Transaction> monthTx) {
        BigDecimal total = sumDebits(monthTx);
        if (total.compareTo(BigDecimal.ZERO) == 0) return 60;
        double lifestyle = computeLifestyleRatio(monthTx, total);
        return (int) Math.round(Math.max(10, Math.max(20, 100 - (lifestyle / 100 * 160))));
    }

    /** EMI/AUTOPAY debits as a fraction of total debits — higher load = lower score. */
    private int scoreDebtServiceLoad(List<Transaction> monthTx, BigDecimal totalDebits) {
        if (totalDebits.compareTo(BigDecimal.ZERO) == 0) return 80;

        double emiDebits = monthTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> t.getMode() == TransactionMode.EMI || t.getMode() == TransactionMode.AUTOPAY)
                .mapToDouble(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount().doubleValue() : 0)
                .sum();

        double ratio = emiDebits / totalDebits.doubleValue();
        if (ratio == 0)    return 100;
        if (ratio <= 0.10) return 90;
        if (ratio <= 0.20) return 75;
        if (ratio <= 0.35) return 55;
        if (ratio <= 0.50) return 35;
        return 15;
    }

    /** Steady weekly spend pattern = high score; front/back heavy or post-salary surge = lower. */
    private int scoreVelocityConsistency(WeeklySpendingVelocityAnalyzer.VelocityResult velocity,
                                         SalaryRunwayAnalyzer.RunwayResult runway) {
        if (velocity == null) return 60;
        int base = switch (velocity.pattern) {
            case "STEADY"      -> 85;
            case "BACK_HEAVY"  -> 65;
            case "FRONT_HEAVY" -> 45;
            default            -> 60;
        };
        if (runway.isAvailable() && runway.postSalarySurge) base -= 15;
        if (runway.isAvailable() && runway.runwayDays != null && runway.runwayDays < 10) base -= 10;
        return Math.max(10, Math.min(100, base));
    }

    /** Fixed recurring payments (EMI + SUBSCRIPTION + RENT + UTILITY) as % of total debits. */
    private int scoreRecurringObligationLoad(List<RecurringExpenseDetector.RecurringExpense> detected,
                                             BigDecimal totalDebits) {
        if (totalDebits.compareTo(BigDecimal.ZERO) == 0) return 80;

        BigDecimal fixedRecurring = detected.stream()
                .filter(r -> r.type != RecurringExpenseDetector.RecurringType.UNKNOWN
                          && r.type != RecurringExpenseDetector.RecurringType.TRANSFER)
                .map(r -> r.totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double ratio = fixedRecurring.doubleValue() / totalDebits.doubleValue();
        if (ratio <= 0.20) return 90;
        if (ratio <= 0.35) return 70;
        if (ratio <= 0.50) return 50;
        if (ratio <= 0.65) return 30;
        return 15;
    }

    private int weightedScore(int discipline, int debtSvc, int velocity, int recurring) {
        return (int) Math.round(
                discipline * W_DISCIPLINE
                + debtSvc  * W_DEBT_SVC
                + velocity * W_VELOCITY
                + recurring * W_RECURRING);
    }

    private String resolveArchetype(int score, int discipline) {
        if (score >= 85) return "The Mindful Spender";
        if (score >= 70) return "The Balanced Spender";
        if (score >= 55) return discipline < 50 ? "The Lifestyle Optimizer" : "The Steady Spender";
        if (score >= 40) return "The Aspirational Improver";
        if (score >= 25) return "The Cash-Flow Juggler";
        return "The Financial Rebuilder";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private BigDecimal sumDebits(List<Transaction> txs) {
        return txs.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCredits(List<Transaction> txs) {
        return txs.stream()
                .filter(t -> t.getType() == TransactionType.CREDIT)
                .map(t -> t.getDepositAmount() != null ? t.getDepositAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private double computeLifestyleRatio(List<Transaction> txs, BigDecimal totalDebits) {
        if (totalDebits.compareTo(BigDecimal.ZERO) == 0) return 0;
        double lifestyle = txs.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT && isLifestyle(t))
                .mapToDouble(t -> t.getWithdrawalAmount() != null
                        ? t.getWithdrawalAmount().doubleValue() : 0)
                .sum();
        return (lifestyle / totalDebits.doubleValue()) * 100;
    }

    private boolean isLifestyle(Transaction t) {
        if (t.getSystemCategory() != null) {
            String cat = t.getSystemCategory().name().toLowerCase();
            return LIFESTYLE_CATS.stream().anyMatch(cat::contains);
        }
        if (t.getCategory() != null) {
            String cat = t.getCategory().toLowerCase();
            return LIFESTYLE_CATS.stream().anyMatch(cat::contains);
        }
        if (t.getCounterpartyName() != null) {
            String name = t.getCounterpartyName().toLowerCase();
            return name.contains("swiggy") || name.contains("zomato")
                    || name.contains("blinkit") || name.contains("zepto")
                    || name.contains("amazon")  || name.contains("flipkart")
                    || name.contains("netflix") || name.contains("spotify");
        }
        return false;
    }

    private RecurringPayment.RecurringType mapType(RecurringExpenseDetector.RecurringType t) {
        if (t == null) return RecurringPayment.RecurringType.REPEATED_DEBIT;
        return switch (t) {
            case SUBSCRIPTION -> RecurringPayment.RecurringType.SUBSCRIPTION;
            case EMI          -> RecurringPayment.RecurringType.EMI;
            case RENT         -> RecurringPayment.RecurringType.RENT;
            default           -> RecurringPayment.RecurringType.REPEATED_DEBIT;
        };
    }

    private String serializeJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { log.error("Failed to serialize profile JSON: {}", e.getMessage()); return "{}"; }
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal log
    // ─────────────────────────────────────────────────────────────────────────

    private void logProfile(MonthlyProfileJson p) {
        String bar  = "═".repeat(65);
        String thin = "─".repeat(65);
        StringBuilder sb = new StringBuilder("\n");
        sb.append(bar).append("\n");
        sb.append(String.format("  SPENDING PROFILE — %s%n", p.getMonth()));
        sb.append(bar).append("\n");
        sb.append(String.format("  Archetype : %s%n", p.getArchetype()));
        sb.append(String.format("  TX Count  : %d%n", p.getTransactionCount()));
        sb.append(thin).append("\n");

        int score = p.getHealthScore();
        sb.append(String.format("  BEHAVIOR SCORE: %d/100  [%s]  %s%n",
                score,
                "█".repeat(score / 5) + "░".repeat(20 - score / 5),
                score >= 75 ? "GOOD" : score >= 50 ? "FAIR" : "POOR"));
        sb.append(thin).append("\n");

        if (p.getSubScores() != null) {
            sb.append("  SUB-SCORES\n");
            MonthlyProfileJson.SubScores s = p.getSubScores();
            sb.append(String.format("  %-30s %3d/100%n", "Spending Discipline",       s.spendingDiscipline));
            sb.append(String.format("  %-30s %3d/100%n", "Debt Service Load",         s.debtServiceLoad));
            sb.append(String.format("  %-30s %3d/100%n", "Velocity Consistency",      s.velocityConsistency));
            sb.append(String.format("  %-30s %3d/100%n", "Recurring Obligation Load", s.recurringObligationLoad));
            sb.append(thin).append("\n");
        }

        if (p.getFinancials() != null) {
            MonthlyProfileJson.Financials f = p.getFinancials();
            sb.append("  STATEMENT TOTALS\n");
            sb.append(String.format("  Total Credits : ₹%s%n", fmt(f.totalCredits)));
            sb.append(String.format("  Total Debits  : ₹%s%n", fmt(f.totalDebits)));
            sb.append(String.format("  Net Flow      : ₹%s%n", fmt(f.netStatementFlow)));
            sb.append(String.format("  Lifestyle     : %.1f%%%n", f.lifestyleRatio));
            sb.append(thin).append("\n");
        }

        if (p.getSpendingBreakdown() != null && !p.getSpendingBreakdown().isEmpty()) {
            sb.append("  SPENDING BY CATEGORY\n");
            p.getSpendingBreakdown().entrySet().stream().limit(6)
                    .forEach(e -> sb.append(String.format("  %-30s ₹%s%n", e.getKey(), fmt(e.getValue()))));
            sb.append(thin).append("\n");
        }

        if (p.getTopMerchants() != null && !p.getTopMerchants().isEmpty()) {
            sb.append("  TOP MERCHANTS\n");
            p.getTopMerchants().forEach(m ->
                    sb.append(String.format("  %-30s ₹%s%n", m.name, fmt(m.amount))));
            sb.append(thin).append("\n");
        }

        if (p.getSalaryRunway() != null && p.getSalaryRunway().insight != null) {
            sb.append("  SALARY RUNWAY\n");
            sb.append("  ").append(p.getSalaryRunway().insight).append("\n");
            sb.append(thin).append("\n");
        }

        if (p.getWeeklyPattern() != null) {
            sb.append(String.format("  WEEKLY: %s%n", p.getWeeklyPattern().pattern));
            sb.append(String.format("  W1:₹%s  W2:₹%s  W3:₹%s  W4:₹%s%n",
                    fmt(p.getWeeklyPattern().week1), fmt(p.getWeeklyPattern().week2),
                    fmt(p.getWeeklyPattern().week3), fmt(p.getWeeklyPattern().week4)));
            sb.append(thin).append("\n");
        }

        if (p.getRecurringPayments() != null && !p.getRecurringPayments().isEmpty()) {
            sb.append("  RECURRING\n");
            p.getRecurringPayments().forEach(r -> sb.append(String.format(
                    "  %-30s ₹%s  [%s]%n", r.merchant, fmt(r.amount), r.type)));
            sb.append(thin).append("\n");
        }

        if (p.getStrengths() != null) {
            sb.append("  STRENGTHS\n");
            p.getStrengths().forEach(s -> sb.append("  ✓ ").append(s).append("\n"));
            sb.append("\n");
        }

        if (p.getRisks() != null) {
            sb.append("  RISKS\n");
            p.getRisks().forEach(r -> sb.append("  ✗ ").append(r).append("\n"));
            sb.append("\n");
        }

        if (p.getRecommendedActions() != null) {
            sb.append("  ACTIONS\n");
            for (int i = 0; i < p.getRecommendedActions().size(); i++)
                sb.append(String.format("  %d. %s%n", i + 1, p.getRecommendedActions().get(i)));
        }

        sb.append(bar).append("\n");
        log.info(sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner helper
    // ─────────────────────────────────────────────────────────────────────────

    private static class SubScoreSet {
        int discipline, debtSvc, velocity, recurring;
        SubScoreSet(int d, int ds, int v, int r) {
            discipline = d; debtSvc = ds; velocity = v; recurring = r;
        }
    }
}
