package com.moneylens.service;

import com.moneylens.dto.FinancialProfileDto;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionMode;
import com.moneylens.entity.TransactionType;
import com.moneylens.entity.User;
import com.moneylens.entity.UserAssessment;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserAssessmentRepository;
import com.moneylens.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes a structured FinancialProfile for a user.
 *
 * Score pipeline:
 *   6 sub-scores (0–100 each) → weighted average → FinancialHealthScore (0–100)
 *
 * Sub-scores:
 *   1. savingsRateScore        (weight 20%) — from assessment income/savings
 *   2. spendingDisciplineScore (weight 20%) — from transaction debit volatility + lifestyle ratio
 *   3. debtBurdenScore         (weight 15%) — from EMI/BNPL transactions + assessment hasDebt
 *   4. incomeStabilityScore    (weight 15%) — from salary/NEFT credit regularity
 *   5. emergencyCushionScore   (weight 15%) — from assessment emergencyFund fields
 *   6. goalAlignmentScore      (weight 15%) — from assessment goal fields + savings trajectory
 *
 * Tier-1 personalisation (wired after core scoring):
 *   - RecurringExpenseDetector   — subscriptions, EMIs, rent, utilities
 *   - SalaryRunwayAnalyzer       — days until 60% of monthly spend consumed
 *   - WeeklySpendingVelocityAnalyzer — week-1 vs week-4 pattern
 *   - DeclaredVsActualAnalyzer   — assessment vs statement cross-check
 *
 * Profile archetypes (assigned by score band + spending pattern):
 *   90–100  → The Wealth Builder
 *   75–89   → The Disciplined Saver
 *   60–74   → The Balanced Spender / The Lifestyle Optimizer
 *   45–59   → The Aspirational Improver / The Debt Wrestler
 *   30–44   → The Cash-Flow Juggler
 *    0–29   → The Financial Rebuilder
 */
@Service
public class FinancialProfileService {

    private static final Logger log = LoggerFactory.getLogger(FinancialProfileService.class);

    // ── Repositories ──────────────────────────────────────────────────────────
    private final UserRepository           userRepository;
    private final UserAssessmentRepository assessmentRepository;
    private final TransactionRepository    transactionRepository;

    // ── Tier-1 analyzers ──────────────────────────────────────────────────────
    private final RecurringExpenseDetector          recurringExpenseDetector;
    private final SalaryRunwayAnalyzer              salaryRunwayAnalyzer;
    private final WeeklySpendingVelocityAnalyzer    weeklyVelocityAnalyzer;
    private final DeclaredVsActualAnalyzer          declaredVsActualAnalyzer;

    // ── Sub-score weights (must sum to 1.0) ───────────────────────────────────
    private static final double W_SAVINGS       = 0.20;
    private static final double W_DISCIPLINE    = 0.20;
    private static final double W_DEBT          = 0.15;
    private static final double W_INCOME_STAB   = 0.15;
    private static final double W_EMERGENCY     = 0.15;
    private static final double W_GOAL          = 0.15;

    // ── Lifestyle category keywords ───────────────────────────────────────────
    private static final Set<String> LIFESTYLE_CATEGORIES = Set.of(
            "food", "dining", "entertainment", "travel", "shopping",
            "drinks", "restaurants", "subscriptions", "fashion"
    );

    public FinancialProfileService(
            UserRepository userRepository,
            UserAssessmentRepository assessmentRepository,
            TransactionRepository transactionRepository,
            RecurringExpenseDetector recurringExpenseDetector,
            SalaryRunwayAnalyzer salaryRunwayAnalyzer,
            WeeklySpendingVelocityAnalyzer weeklyVelocityAnalyzer,
            DeclaredVsActualAnalyzer declaredVsActualAnalyzer) {

        this.userRepository            = userRepository;
        this.assessmentRepository      = assessmentRepository;
        this.transactionRepository     = transactionRepository;
        this.recurringExpenseDetector  = recurringExpenseDetector;
        this.salaryRunwayAnalyzer      = salaryRunwayAnalyzer;
        this.weeklyVelocityAnalyzer    = weeklyVelocityAnalyzer;
        this.declaredVsActualAnalyzer  = declaredVsActualAnalyzer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds (or refreshes) the complete financial profile for a user.
     * Returns a fully populated FinancialProfileDto ready for the AI copilot context.
     */
    public FinancialProfileDto buildProfile(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found: " + phoneNumber));

        UserAssessment assessment = assessmentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Assessment not completed for user: " + phoneNumber));

        List<Transaction> allTx = transactionRepository.findByUserIdOrderByDateDesc(user.getId());

        // ── 1. Core sub-scores ─────────────────────────────────────────────
        SubScores sub = new SubScores();
        sub.savingsRate        = scoreSavingsRate(assessment);
        sub.spendingDiscipline = scoreSpendingDiscipline(assessment, allTx);
        sub.debtBurden         = scoreDebtBurden(assessment, allTx);
        sub.incomeStability    = scoreIncomeStability(assessment, allTx);
        sub.emergencyCushion   = scoreEmergencyCushion(assessment);
        sub.goalAlignment      = scoreGoalAlignment(assessment);

        int    healthScore = weightedScore(sub);
        String archetype   = resolveArchetype(healthScore, sub, assessment);

        // ── 2. Spending breakdown ──────────────────────────────────────────
        SpendingBreakdown breakdown = computeSpendingBreakdown(allTx);

        // ── 3. Strengths / risks / actions ────────────────────────────────
        List<String> strengths = identifyStrengths(sub, assessment);
        List<String> risks     = identifyRisks(sub, assessment, breakdown);
        List<String> actions   = generateActions(sub, assessment, breakdown);

        // ── 4. Tier-1 personalisation analytics ───────────────────────────
        List<RecurringExpenseDetector.RecurringExpense> recurring =
                recurringExpenseDetector.detect(allTx);

        SalaryRunwayAnalyzer.RunwayResult runway =
                salaryRunwayAnalyzer.analyze(allTx);

        WeeklySpendingVelocityAnalyzer.VelocityResult velocity =
                weeklyVelocityAnalyzer.analyze(allTx);

        DeclaredVsActualAnalyzer.DiscrepancyReport discrepancy =
                declaredVsActualAnalyzer.analyze(assessment, allTx, recurring);

        // ── 5. Assemble DTO ────────────────────────────────────────────────
        FinancialProfileDto profile = new FinancialProfileDto();

        // Identity & scores
        profile.setUserId(user.getId());
        profile.setFullName(assessment.getFullName());
        profile.setArchetype(archetype);
        profile.setHealthScore(healthScore);
        profile.setSavingsRateScore(sub.savingsRate);
        profile.setSpendingDisciplineScore(sub.spendingDiscipline);
        profile.setDebtBurdenScore(sub.debtBurden);
        profile.setIncomeStabilityScore(sub.incomeStability);
        profile.setEmergencyCushionScore(sub.emergencyCushion);
        profile.setGoalAlignmentScore(sub.goalAlignment);

        // Assessment snapshot
        profile.setMonthlyIncome(assessment.getMonthlyIncome());
        profile.setMonthlySavings(assessment.getMonthlySavings());
        profile.setFinancialGoal(assessment.getFinancialGoal());
        profile.setGoalAmount(assessment.getGoalAmount());
        profile.setGoalDeadline(assessment.getGoalDeadline());
        profile.setOccupation(assessment.getOccupation());

        // Transaction analytics
        profile.setSpendingBreakdown(breakdown.categoryTotals);
        profile.setAvgMonthlySpend(breakdown.avgMonthlyDebit);
        profile.setTopMerchants(breakdown.topMerchants);
        profile.setLifestyleRatio(breakdown.lifestyleRatio);

        // Insights
        profile.setStrengths(strengths);
        profile.setRisks(risks);
        profile.setRecommendedActions(actions);

        // Tier-1 personalisation
        profile.setRecurringExpenses(recurring);
        profile.setSalaryRunway(runway);
        profile.setWeeklyVelocity(velocity);
        profile.setDiscrepancyReport(discrepancy);

        // Meta
        profile.setTotalTransactionsAnalyzed(allTx.size());
        profile.setProfileGeneratedAt(LocalDate.now());

        // ── 6. Terminal log ────────────────────────────────────────────────
        logProfile(profile, sub, breakdown, recurring, runway, velocity, discrepancy);

        return profile;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sub-score calculators
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Savings Rate Score (0–100)
     * Based on: monthlySavings / monthlyIncome
     *
     * Band table (financial thumb-rules):
     *   >= 40%  → 100  (excellent)
     *   >= 30%  →  85
     *   >= 20%  →  70  (recommended baseline)
     *   >= 10%  →  50
     *   >= 5%   →  30
     *    < 5%   →  10  (critical)
     */
    private int scoreSavingsRate(UserAssessment a) {
        if (a.getMonthlyIncome() == null || a.getMonthlySavings() == null) return 50;
        if (a.getMonthlyIncome().compareTo(BigDecimal.ZERO) == 0) return 10;

        double rate = a.getMonthlySavings().doubleValue()
                / a.getMonthlyIncome().doubleValue();

        if (rate >= 0.40) return 100;
        if (rate >= 0.30) return 85;
        if (rate >= 0.20) return 70;
        if (rate >= 0.10) return 50;
        if (rate >= 0.05) return 30;
        return 10;
    }

    /**
     * Spending Discipline Score (0–100)
     * Combines:
     *   a) Lifestyle ratio (food + entertainment + shopping / total debits)
     *      Lower is better — excessive discretionary spend penalises score
     *   b) Month-over-month debit volatility (coefficient of variation)
     *      High variance = impulsive spending = penalised
     */
    private int scoreSpendingDiscipline(UserAssessment assessment, List<Transaction> allTx) {
        List<Transaction> debits = allTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .collect(Collectors.toList());

        if (debits.isEmpty()) return 60; // neutral when no data

        // Lifestyle ratio
        double totalDebit = debits.stream()
                .mapToDouble(t -> safeDouble(t.getWithdrawalAmount()))
                .sum();

        double lifestyleDebit = debits.stream()
                .filter(this::isLifestyleTransaction)
                .mapToDouble(t -> safeDouble(t.getWithdrawalAmount()))
                .sum();

        double lifestyleRatio = totalDebit > 0 ? lifestyleDebit / totalDebit : 0;

        // MoM volatility (coefficient of variation of monthly totals)
        Map<String, Double> monthlyTotals = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getStatementYear() + "-" + String.format("%02d", t.getStatementMonth()),
                        Collectors.summingDouble(t -> safeDouble(t.getWithdrawalAmount()))
                ));

        double volatilityPenalty = 0;
        if (monthlyTotals.size() >= 2) {
            double[] vals = monthlyTotals.values().stream()
                    .mapToDouble(Double::doubleValue).toArray();
            double mean     = Arrays.stream(vals).average().orElse(0);
            double variance = Arrays.stream(vals).map(v -> Math.pow(v - mean, 2)).average().orElse(0);
            double cv       = mean > 0 ? Math.sqrt(variance) / mean : 0;
            volatilityPenalty = Math.min(cv * 30, 30); // max 30-point penalty
        }

        // Lifestyle ratio score: 0% → 100pts, 50%+ → 20pts
        double lifestyleScore = Math.max(20, 100 - (lifestyleRatio * 160));

        return (int) Math.round(Math.max(10, lifestyleScore - volatilityPenalty));
    }

    /**
     * Debt Burden Score (0–100)
     * Higher = less debt burden (better)
     * Sources: assessment.hasDebt flag + EMI/AUTOPAY transaction patterns
     */
    private int scoreDebtBurden(UserAssessment assessment, List<Transaction> allTx) {
        int base = 80; // assume moderate

        if (Boolean.FALSE.equals(assessment.getHasDebt())) return 100;
        if (Boolean.TRUE.equals(assessment.getHasDebt()))  base -= 25;

        // Transaction-derived EMI load
        double totalDebit = allTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .mapToDouble(t -> safeDouble(t.getWithdrawalAmount()))
                .sum();

        double emiDebit = allTx.stream()
                .filter(t -> (t.getMode() == TransactionMode.EMI
                        || t.getMode() == TransactionMode.AUTOPAY)
                        && t.getType() == TransactionType.DEBIT)
                .mapToDouble(t -> safeDouble(t.getWithdrawalAmount()))
                .sum();

        // FOIR (Fixed Obligation-to-Income Ratio) proxy
        if (assessment.getMonthlyIncome() != null && assessment.getMonthlyIncome().doubleValue() > 0) {
            long months = allTx.stream()
                    .map(t -> t.getStatementYear() + "-" + t.getStatementMonth())
                    .distinct().count();
            double monthlyEmi = months > 0 ? emiDebit / months : 0;
            double foir       = monthlyEmi / assessment.getMonthlyIncome().doubleValue();
            // RBI guidance: FOIR > 50% is high-risk
            if      (foir > 0.50) base -= 30;
            else if (foir > 0.35) base -= 15;
            else if (foir > 0.20) base -= 5;
        } else {
            // No income data — use EMI ratio as proxy
            double emiRatio = totalDebit > 0 ? emiDebit / totalDebit : 0;
            if      (emiRatio > 0.40) base -= 25;
            else if (emiRatio > 0.25) base -= 10;
        }

        // BNPL / loan app detection
        boolean hasBnpl = allTx.stream().anyMatch(t ->
                t.getCounterpartyName() != null && (
                        t.getCounterpartyName().toLowerCase().contains("kreditbee")
                                || t.getCounterpartyName().toLowerCase().contains("lazypay")
                                || t.getCounterpartyName().toLowerCase().contains("simpl")
                                || t.getCounterpartyName().toLowerCase().contains("zestmoney")
                                || t.getCounterpartyName().toLowerCase().contains("navi")
                                || t.getCounterpartyName().toLowerCase().contains("moneyview")));
        if (hasBnpl) base -= 15;

        return Math.max(10, Math.min(100, base));
    }

    /**
     * Income Stability Score (0–100)
     * Measures how consistent and predictable income credits are.
     */
    private int scoreIncomeStability(UserAssessment assessment, List<Transaction> allTx) {
        List<Transaction> incomeCredits = allTx.stream()
                .filter(t -> t.getType() == TransactionType.CREDIT)
                .filter(this::isIncomeTransaction)
                .collect(Collectors.toList());

        Map<String, Double> monthlyIncome = incomeCredits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getStatementYear() + "-" + String.format("%02d", t.getStatementMonth()),
                        Collectors.summingDouble(t -> safeDouble(t.getDepositAmount()))
                ));

        long totalMonths = allTx.stream()
                .map(t -> t.getStatementYear() + "-" + t.getStatementMonth())
                .distinct().count();

        if (totalMonths == 0) return 50;

        double coverage = (double) monthlyIncome.size() / totalMonths;

        double variancePenalty = 0;
        if (monthlyIncome.size() >= 2) {
            double[] vals = monthlyIncome.values().stream()
                    .mapToDouble(Double::doubleValue).toArray();
            double mean = Arrays.stream(vals).average().orElse(0);
            double cv   = mean > 0
                    ? Math.sqrt(Arrays.stream(vals).map(v -> Math.pow(v - mean, 2)).average().orElse(0)) / mean
                    : 1;
            variancePenalty = Math.min(cv * 20, 20);
        }

        int occupationAdjust = 0;
        if (assessment.getOccupation() != null) {
            String occ = assessment.getOccupation().toLowerCase();
            if (occ.contains("salaried") || occ.contains("government")) occupationAdjust = +10;
            if (occ.contains("freelance") || occ.contains("self-employed")) occupationAdjust = -10;
        }

        double base = (coverage * 90) - variancePenalty + occupationAdjust;
        return (int) Math.round(Math.max(10, Math.min(100, base)));
    }

    /**
     * Emergency Cushion Score (0–100)
     * Rule: 6+ months = ideal; 3–5 = adequate; 1–2 = insufficient; 0 = critical
     */
    private int scoreEmergencyCushion(UserAssessment assessment) {
        if (Boolean.FALSE.equals(assessment.getHasEmergencyFund())) return 10;
        if (Boolean.TRUE.equals(assessment.getHasEmergencyFund())) {
            Integer months = assessment.getEmergencyMonths();
            if (months == null) return 50;
            if (months >= 6)  return 100;
            if (months >= 4)  return 80;
            if (months >= 2)  return 55;
            if (months >= 1)  return 30;
            return 10;
        }
        return 40; // not answered — neutral
    }

    /**
     * Goal Alignment Score (0–100)
     * Measures whether the user's savings trajectory supports their financial goal.
     */
    private int scoreGoalAlignment(UserAssessment assessment) {
        int score = 30; // base: no goal set

        if (assessment.getFinancialGoal() == null || assessment.getFinancialGoal().isBlank()) {
            return score;
        }
        score = 50; // has a goal

        if (assessment.getGoalDeadline() == null) return score;
        score = 60;

        if (assessment.getGoalAmount() == null || assessment.getMonthlySavings() == null
                || assessment.getMonthlyIncome() == null) return score;
        score = 70;

        LocalDate now        = LocalDate.now();
        LocalDate target     = assessment.getGoalDeadline();
        long      monthsLeft = java.time.temporal.ChronoUnit.MONTHS.between(now, target);

        if (monthsLeft <= 0) return 40; // past deadline

        BigDecimal projectedSavings = assessment.getMonthlySavings()
                .multiply(new BigDecimal(monthsLeft));
        int cmp = projectedSavings.compareTo(assessment.getGoalAmount());

        if (cmp >= 0)   return 100;  // on track
        double ratio = projectedSavings.doubleValue() / assessment.getGoalAmount().doubleValue();
        if (ratio >= 0.75) return 85;
        if (ratio >= 0.50) return 70;
        if (ratio >= 0.25) return 55;
        return 40;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Weighted composite
    // ─────────────────────────────────────────────────────────────────────────

    private int weightedScore(SubScores s) {
        double raw = s.savingsRate        * W_SAVINGS
                + s.spendingDiscipline * W_DISCIPLINE
                + s.debtBurden         * W_DEBT
                + s.incomeStability    * W_INCOME_STAB
                + s.emergencyCushion   * W_EMERGENCY
                + s.goalAlignment      * W_GOAL;
        return (int) Math.round(raw);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Archetype resolver
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveArchetype(int score, SubScores sub, UserAssessment assessment) {
        if (score >= 90) return "The Wealth Builder";
        if (score >= 75) return "The Disciplined Saver";
        if (score >= 60) {
            return sub.spendingDiscipline < 50 ? "The Lifestyle Optimizer" : "The Balanced Spender";
        }
        if (score >= 45) {
            return sub.debtBurden < 50 ? "The Debt Wrestler" : "The Aspirational Improver";
        }
        if (score >= 30) return "The Cash-Flow Juggler";
        return "The Financial Rebuilder";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spending breakdown
    // ─────────────────────────────────────────────────────────────────────────

    private SpendingBreakdown computeSpendingBreakdown(List<Transaction> allTx) {
        SpendingBreakdown breakdown = new SpendingBreakdown();

        List<Transaction> debits = allTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .collect(Collectors.toList());

        if (debits.isEmpty()) return breakdown;

        breakdown.categoryTotals = debits.stream()
                .filter(t -> t.getCategory() != null)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.summingDouble(t -> safeDouble(t.getWithdrawalAmount()))
                ));

        double totalDebit = debits.stream()
                .mapToDouble(t -> safeDouble(t.getWithdrawalAmount()))
                .sum();

        double lifestyleDebit = debits.stream()
                .filter(this::isLifestyleTransaction)
                .mapToDouble(t -> safeDouble(t.getWithdrawalAmount()))
                .sum();

        breakdown.lifestyleRatio = totalDebit > 0
                ? BigDecimal.valueOf(lifestyleDebit / totalDebit * 100)
                .setScale(1, RoundingMode.HALF_UP).doubleValue()
                : 0;

        long months = allTx.stream()
                .map(t -> t.getStatementYear() + "-" + t.getStatementMonth())
                .distinct().count();
        breakdown.avgMonthlyDebit = months > 0
                ? BigDecimal.valueOf(totalDebit / months).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        breakdown.topMerchants = debits.stream()
                .filter(t -> t.getCounterpartyName() != null || t.getMerchantName() != null)
                .collect(Collectors.groupingBy(
                        t -> t.getMerchantName() != null ? t.getMerchantName() : t.getCounterpartyName(),
                        Collectors.summingDouble(t -> safeDouble(t.getWithdrawalAmount()))
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        return breakdown;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strengths / Risks / Actions
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> identifyStrengths(SubScores sub, UserAssessment assessment) {
        List<String> strengths = new ArrayList<>();
        if (sub.savingsRate >= 70)        strengths.add("Strong savings discipline — saving well above 20% of income");
        if (sub.debtBurden >= 80)         strengths.add("Low debt exposure — healthy financial flexibility");
        if (sub.incomeStability >= 75)    strengths.add("Consistent income pattern — stable cash flow foundation");
        if (sub.emergencyCushion >= 80)   strengths.add("Adequate emergency fund — 4+ months coverage");
        if (sub.spendingDiscipline >= 75) strengths.add("Controlled discretionary spending — disciplined lifestyle choices");
        if (sub.goalAlignment >= 80)      strengths.add("Goal trajectory on track — projected savings match target");
        if (Boolean.FALSE.equals(assessment.getHasDebt()))
            strengths.add("Debt-free status — full income available for wealth building");
        if (strengths.isEmpty())
            strengths.add("Taking proactive steps to understand and improve your finances");
        return strengths;
    }

    private List<String> identifyRisks(SubScores sub, UserAssessment assessment,
                                       SpendingBreakdown breakdown) {
        List<String> risks = new ArrayList<>();
        if (sub.savingsRate <= 30)         risks.add("Very low savings rate — vulnerable to financial shocks");
        if (sub.debtBurden <= 40)          risks.add("High debt burden — significant portion of income going to fixed obligations");
        if (sub.emergencyCushion <= 30)    risks.add("Insufficient emergency fund — less than 2 months coverage");
        if (sub.spendingDiscipline <= 40)  risks.add("High lifestyle inflation — discretionary spending limiting wealth building");
        if (sub.incomeStability <= 40)     risks.add("Irregular income pattern — cash flow volatility risk");
        if (sub.goalAlignment <= 40)       risks.add("Goal underfunded — current savings pace won't meet target by deadline");
        if (breakdown.lifestyleRatio > 50) risks.add("Over 50% of spending is discretionary — significant optimisation potential");
        return risks;
    }

    private List<String> generateActions(SubScores sub, UserAssessment assessment,
                                         SpendingBreakdown breakdown) {
        List<String> actions = new ArrayList<>();

        if (sub.savingsRate < 60) {
            double income   = assessment.getMonthlyIncome() != null ? assessment.getMonthlyIncome().doubleValue() : 0;
            double target20 = income * 0.20;
            actions.add(String.format("Automate ₹%.0f/month to a dedicated savings account (20%% target)", target20));
        }
        if (sub.emergencyCushion < 60) {
            actions.add("Build emergency fund: target 6x monthly expenses in a liquid account before investing");
        }
        if (sub.debtBurden < 50) {
            actions.add("Prioritise high-interest debt repayment using the avalanche method");
        }
        if (sub.spendingDiscipline < 60) {
            actions.add("Set a monthly discretionary budget; use the 50/30/20 rule as a starting framework");
        }
        if (sub.goalAlignment < 60 && assessment.getGoalAmount() != null && assessment.getGoalDeadline() != null) {
            actions.add("Review goal timeline or increase monthly savings to close the goal funding gap");
        }
        if (breakdown.topMerchants != null && !breakdown.topMerchants.isEmpty()) {
            String topMerchant = breakdown.topMerchants.keySet().iterator().next();
            actions.add("Your top spend: " + topMerchant + " — review if this aligns with your priorities");
        }
        if (Boolean.FALSE.equals(assessment.getHasEmergencyFund())) {
            actions.add("Start a ₹500/week emergency fund habit before adding any new financial goals");
        }
        if (actions.isEmpty()) {
            actions.add("Continue current habits and consider stepping up SIP contributions by 10% annually");
        }
        return actions;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transaction classifiers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isLifestyleTransaction(Transaction t) {
        if (t.getCategory() != null) {
            String cat = t.getCategory().toLowerCase();
            return LIFESTYLE_CATEGORIES.stream().anyMatch(cat::contains);
        }
        if (t.getCounterpartyName() != null) {
            String name = t.getCounterpartyName().toLowerCase();
            return name.contains("swiggy") || name.contains("zomato") || name.contains("blinkit")
                    || name.contains("zepto")  || name.contains("amazon") || name.contains("flipkart")
                    || name.contains("bookmyshow") || name.contains("netflix") || name.contains("spotify");
        }
        return false;
    }

    private boolean isIncomeTransaction(Transaction t) {
        if (t.getMode() == null && t.getRawNarration() == null) return false;
        String narration = t.getRawNarration() != null ? t.getRawNarration().toUpperCase() : "";
        return narration.contains("SALARY") || narration.contains("NEFT CR")
                || narration.contains("IMPS CR") || narration.contains("RTGS CR")
                || narration.contains("CASHBACK") || narration.contains("INTEREST");
    }

    private double safeDouble(BigDecimal val) {
        return val != null ? val.doubleValue() : 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal profile logging
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prints a structured, human-readable financial profile to the backend terminal.
     * Runs after every profile build so developers can inspect scores in real time
     * without opening a DB client or enabling SQL logging.
     * Now includes Tier-1 personalisation sections.
     */
    private void logProfile(
            FinancialProfileDto p,
            SubScores sub,
            SpendingBreakdown breakdown,
            List<RecurringExpenseDetector.RecurringExpense> recurring,
            SalaryRunwayAnalyzer.RunwayResult runway,
            WeeklySpendingVelocityAnalyzer.VelocityResult velocity,
            DeclaredVsActualAnalyzer.DiscrepancyReport discrepancy) {

        String bar  = "═".repeat(65);
        String thin = "─".repeat(65);
        StringBuilder sb = new StringBuilder("\n");

        sb.append(bar).append("\n");
        sb.append("  💰 MONEYLENS — FINANCIAL PROFILE REPORT\n");
        sb.append(bar).append("\n");

        // ── Identity ──────────────────────────────────────────────────────
        sb.append(String.format("  User ID   : %d%n",   p.getUserId()));
        sb.append(String.format("  Name      : %s%n",   p.getFullName()));
        sb.append(String.format("  Archetype : %s%n",   p.getArchetype()));
        sb.append(String.format("  Generated : %s%n",   p.getProfileGeneratedAt()));
        sb.append(String.format("  TX Count  : %d transactions analysed%n", p.getTotalTransactionsAnalyzed()));
        sb.append(thin).append("\n");

        // ── Health score ──────────────────────────────────────────────────
        int    score  = p.getHealthScore();
        int    filled = score / 5;
        String bar20  = "█".repeat(filled) + "░".repeat(20 - filled);
        String grade  = score >= 75 ? "GOOD" : score >= 50 ? "FAIR" : "POOR";
        sb.append(String.format("  FINANCIAL HEALTH SCORE: %d / 100  [%s]  (%s)%n", score, bar20, grade));
        sb.append(thin).append("\n");

        // ── Sub-scores ────────────────────────────────────────────────────
        sb.append("  SUB-SCORES (weighted)\n");
        sb.append(String.format("  %-28s %3d / 100  %s  (20%%)%n", "Savings Rate",          sub.savingsRate,        miniBar(sub.savingsRate)));
        sb.append(String.format("  %-28s %3d / 100  %s  (20%%)%n", "Spending Discipline",   sub.spendingDiscipline, miniBar(sub.spendingDiscipline)));
        sb.append(String.format("  %-28s %3d / 100  %s  (15%%)%n", "Debt Burden",           sub.debtBurden,         miniBar(sub.debtBurden)));
        sb.append(String.format("  %-28s %3d / 100  %s  (15%%)%n", "Income Stability",      sub.incomeStability,    miniBar(sub.incomeStability)));
        sb.append(String.format("  %-28s %3d / 100  %s  (15%%)%n", "Emergency Cushion",     sub.emergencyCushion,   miniBar(sub.emergencyCushion)));
        sb.append(String.format("  %-28s %3d / 100  %s  (15%%)%n", "Goal Alignment",        sub.goalAlignment,      miniBar(sub.goalAlignment)));
        sb.append(thin).append("\n");

        // ── Financial snapshot ────────────────────────────────────────────
        sb.append("  FINANCIAL SNAPSHOT\n");
        sb.append(String.format("  Monthly Income     : ₹%s%n",
                p.getMonthlyIncome()   != null ? formatRupee(p.getMonthlyIncome().doubleValue())   : "N/A"));
        sb.append(String.format("  Monthly Savings    : ₹%s%n",
                p.getMonthlySavings()  != null ? formatRupee(p.getMonthlySavings().doubleValue())  : "N/A"));
        sb.append(String.format("  Avg Monthly Spend  : ₹%s%n",
                p.getAvgMonthlySpend() != null ? formatRupee(p.getAvgMonthlySpend().doubleValue()) : "N/A"));
        sb.append(String.format("  Lifestyle Ratio    : %.1f%% of total spend%n", p.getLifestyleRatio()));
        sb.append(String.format("  Occupation         : %s%n",
                p.getOccupation() != null ? p.getOccupation() : "N/A"));
        sb.append(thin).append("\n");

        // ── Goal ──────────────────────────────────────────────────────────
        sb.append("  FINANCIAL GOAL\n");
        sb.append(String.format("  Goal     : %s%n",
                p.getFinancialGoal() != null ? p.getFinancialGoal() : "Not set"));
        sb.append(String.format("  Target   : ₹%s%n",
                p.getGoalAmount() != null ? formatRupee(p.getGoalAmount().doubleValue()) : "N/A"));
        sb.append(String.format("  Deadline : %s%n",
                p.getGoalDeadline() != null ? p.getGoalDeadline().toString() : "Not set"));
        sb.append(thin).append("\n");

        // ── Top merchants ─────────────────────────────────────────────────
        if (breakdown.topMerchants != null && !breakdown.topMerchants.isEmpty()) {
            sb.append("  TOP MERCHANTS BY SPEND\n");
            int rank = 1;
            for (Map.Entry<String, Double> entry : breakdown.topMerchants.entrySet()) {
                sb.append(String.format("  %d. %-30s ₹%s%n",
                        rank++, entry.getKey(), formatRupee(entry.getValue())));
            }
            sb.append(thin).append("\n");
        }

        // ── Category breakdown ────────────────────────────────────────────
        if (breakdown.categoryTotals != null && !breakdown.categoryTotals.isEmpty()) {
            sb.append("  SPENDING BY CATEGORY\n");
            breakdown.categoryTotals.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(8)
                    .forEach(e -> sb.append(String.format("  %-30s ₹%s%n",
                            e.getKey(), formatRupee(e.getValue()))));
            sb.append(thin).append("\n");
        }

        // ── [NEW] Salary runway ───────────────────────────────────────────
        if (runway != null && runway.insight != null) {
            sb.append("  SALARY RUNWAY\n");
            sb.append("  ").append(runway.insight).append("\n");
            if (runway.firstWeekSpend != null)
                sb.append(String.format("  Week-1 spend  : ₹%s%n", formatRupee(runway.firstWeekSpend.doubleValue())));
            if (runway.lastWeekSpend != null)
                sb.append(String.format("  Last-wk spend : ₹%s%n", formatRupee(runway.lastWeekSpend.doubleValue())));
            if (runway.runwayDays != null)
                sb.append(String.format("  Runway days   : %d days to 60%% spend%n", runway.runwayDays));
            sb.append(thin).append("\n");
        }

        // ── [NEW] Weekly spending pattern ─────────────────────────────────
        if (velocity != null) {
            sb.append(String.format("  WEEKLY PATTERN: %s%n", velocity.pattern));
            sb.append("  ").append(velocity.insight).append("\n");
            sb.append(String.format("  W1: ₹%s | W2: ₹%s | W3: ₹%s | W4: ₹%s  (ratio: %.2f)%n",
                    formatRupee(velocity.week1.doubleValue()),
                    formatRupee(velocity.week2.doubleValue()),
                    formatRupee(velocity.week3.doubleValue()),
                    formatRupee(velocity.week4.doubleValue()),
                    velocity.week1ToWeek4Ratio));
            sb.append(thin).append("\n");
        }

        // ── [NEW] Recurring expenses ──────────────────────────────────────
        if (recurring != null && !recurring.isEmpty()) {
            sb.append("  RECURRING EXPENSES DETECTED\n");
            for (RecurringExpenseDetector.RecurringExpense r : recurring) {
                sb.append(String.format("  %-30s ₹%s  [%s]%n",
                        r.merchant,
                        formatRupee(r.totalAmount.doubleValue()),
                        r.type));
            }
            sb.append(thin).append("\n");
        }

        // ── [NEW] Declared vs actual ──────────────────────────────────────
        if (discrepancy != null) {
            sb.append("  DECLARED vs ACTUAL CROSS-CHECK\n");
            sb.append("  Overall : ").append(discrepancy.overallSummary).append("\n");
            sb.append("  Income  : ").append(discrepancy.incomeInsight).append("\n");
            sb.append("  Savings : ").append(discrepancy.savingsInsight).append("\n");
            sb.append("  Debt    : ").append(discrepancy.debtInsight).append("\n");
            sb.append(thin).append("\n");
        }

        // ── Strengths ─────────────────────────────────────────────────────
        sb.append("  STRENGTHS\n");
        if (p.getStrengths() != null) {
            p.getStrengths().forEach(s -> sb.append("  ✓ ").append(s).append("\n"));
        }
        sb.append("\n");

        // ── Risks ─────────────────────────────────────────────────────────
        sb.append("  RISKS\n");
        if (p.getRisks() != null) {
            p.getRisks().forEach(r -> sb.append("  ✗ ").append(r).append("\n"));
        }
        sb.append("\n");

        // ── Actions ───────────────────────────────────────────────────────
        sb.append("  RECOMMENDED ACTIONS\n");
        if (p.getRecommendedActions() != null) {
            int i = 1;
            for (String action : p.getRecommendedActions()) {
                sb.append(String.format("  %d. %s%n", i++, action));
            }
        }

        sb.append(bar).append("\n");
        log.info(sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Renders a 10-char mini progress bar for a 0–100 score. */
    private String miniBar(int score) {
        int filled = Math.round(score / 10f);
        return "[" + "█".repeat(filled) + "░".repeat(10 - filled) + "]";
    }

    /** Formats a double as Indian rupee string (e.g. 1,23,456 or 1.23 L). */
    private String formatRupee(double amount) {
        if (amount >= 10_00_000) {
            return String.format("%.2f L", amount / 1_00_000);
        }
        long val = Math.round(amount);
        if (val < 1000) return String.valueOf(val);
        String s      = String.valueOf(val);
        int    len    = s.length();
        int    first  = len % 2 == 0 ? 2 : (len > 3 ? 1 : 3);
        StringBuilder result = new StringBuilder(s.substring(0, first));
        for (int i = first; i < len - 3; i += 2) {
            result.append(",").append(s, i, i + 2);
        }
        result.append(",").append(s.substring(len - 3));
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

    private static class SubScores {
        int savingsRate;
        int spendingDiscipline;
        int debtBurden;
        int incomeStability;
        int emergencyCushion;
        int goalAlignment;
    }

    private static class SpendingBreakdown {
        Map<String, Double> categoryTotals = new LinkedHashMap<>();
        Map<String, Double> topMerchants   = new LinkedHashMap<>();
        BigDecimal avgMonthlyDebit         = BigDecimal.ZERO;
        double     lifestyleRatio          = 0;
    }
}