package com.moneylens.dto;

import com.moneylens.service.DeclaredVsActualAnalyzer;
import com.moneylens.service.RecurringExpenseDetector;
import com.moneylens.service.SalaryRunwayAnalyzer;
import com.moneylens.service.WeeklySpendingVelocityAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Complete financial profile for a user.
 * Passed as context to the AI Copilot for personalized advice.
 *
 * Includes:
 *   - 6 sub-scores + composite health score
 *   - Assessment-derived snapshot (income, savings, goal)
 *   - Transaction-derived analytics (spending breakdown, top merchants)
 *   - Tier-1 personalisation: recurring expenses, salary runway,
 *     weekly velocity, declared-vs-actual discrepancies
 */
public class FinancialProfileDto {

    // ── Identity ──────────────────────────────────────────────────────────────
    private Long    userId;
    private String  fullName;
    private String  archetype;                   // e.g. "The Disciplined Saver"

    // ── Composite score ───────────────────────────────────────────────────────
    private int healthScore;                     // 0–100

    // ── Sub-scores ────────────────────────────────────────────────────────────
    private int savingsRateScore;
    private int spendingDisciplineScore;
    private int debtBurdenScore;
    private int incomeStabilityScore;
    private int emergencyCushionScore;
    private int goalAlignmentScore;

    // ── Assessment-derived context ────────────────────────────────────────────
    private BigDecimal monthlyIncome;
    private BigDecimal monthlySavings;
    private String     occupation;
    private String     financialGoal;
    private BigDecimal goalAmount;
    private LocalDate  goalDeadline;

    // ── Transaction-derived analytics ─────────────────────────────────────────
    private Map<String, Double> spendingBreakdown;   // category → total amount
    private Map<String, Double> topMerchants;        // merchant → total spend
    private BigDecimal avgMonthlySpend;
    private double     lifestyleRatio;               // % of spend that is discretionary

    // ── Core insights ─────────────────────────────────────────────────────────
    private List<String> strengths;
    private List<String> risks;
    private List<String> recommendedActions;

    // ── Tier-1 personalisation analytics ──────────────────────────────────────
    private List<RecurringExpenseDetector.RecurringExpense> recurringExpenses;
    private SalaryRunwayAnalyzer.RunwayResult               salaryRunway;
    private WeeklySpendingVelocityAnalyzer.VelocityResult   weeklyVelocity;
    private DeclaredVsActualAnalyzer.DiscrepancyReport      discrepancyReport;

    // ── Meta ──────────────────────────────────────────────────────────────────
    private int       totalTransactionsAnalyzed;
    private LocalDate profileGeneratedAt;

    // ─────────────────────────────────────────────────────────────────────────
    // Getters & Setters — base fields
    // ─────────────────────────────────────────────────────────────────────────

    public Long getUserId()                  { return userId; }
    public void setUserId(Long v)            { this.userId = v; }

    public String getFullName()              { return fullName; }
    public void setFullName(String v)        { this.fullName = v; }

    public String getArchetype()             { return archetype; }
    public void setArchetype(String v)       { this.archetype = v; }

    public int getHealthScore()              { return healthScore; }
    public void setHealthScore(int v)        { this.healthScore = v; }

    public int getSavingsRateScore()         { return savingsRateScore; }
    public void setSavingsRateScore(int v)   { this.savingsRateScore = v; }

    public int getSpendingDisciplineScore()       { return spendingDisciplineScore; }
    public void setSpendingDisciplineScore(int v) { this.spendingDisciplineScore = v; }

    public int getDebtBurdenScore()          { return debtBurdenScore; }
    public void setDebtBurdenScore(int v)    { this.debtBurdenScore = v; }

    public int getIncomeStabilityScore()         { return incomeStabilityScore; }
    public void setIncomeStabilityScore(int v)   { this.incomeStabilityScore = v; }

    public int getEmergencyCushionScore()        { return emergencyCushionScore; }
    public void setEmergencyCushionScore(int v)  { this.emergencyCushionScore = v; }

    public int getGoalAlignmentScore()       { return goalAlignmentScore; }
    public void setGoalAlignmentScore(int v) { this.goalAlignmentScore = v; }

    public BigDecimal getMonthlyIncome()         { return monthlyIncome; }
    public void setMonthlyIncome(BigDecimal v)   { this.monthlyIncome = v; }

    public BigDecimal getMonthlySavings()        { return monthlySavings; }
    public void setMonthlySavings(BigDecimal v)  { this.monthlySavings = v; }

    public String getOccupation()            { return occupation; }
    public void setOccupation(String v)      { this.occupation = v; }

    public String getFinancialGoal()         { return financialGoal; }
    public void setFinancialGoal(String v)   { this.financialGoal = v; }

    public BigDecimal getGoalAmount()        { return goalAmount; }
    public void setGoalAmount(BigDecimal v)  { this.goalAmount = v; }

    public LocalDate getGoalDeadline()       { return goalDeadline; }
    public void setGoalDeadline(LocalDate v) { this.goalDeadline = v; }

    public Map<String, Double> getSpendingBreakdown()          { return spendingBreakdown; }
    public void setSpendingBreakdown(Map<String, Double> v)    { this.spendingBreakdown = v; }

    public Map<String, Double> getTopMerchants()               { return topMerchants; }
    public void setTopMerchants(Map<String, Double> v)         { this.topMerchants = v; }

    public BigDecimal getAvgMonthlySpend()       { return avgMonthlySpend; }
    public void setAvgMonthlySpend(BigDecimal v) { this.avgMonthlySpend = v; }

    public double getLifestyleRatio()        { return lifestyleRatio; }
    public void setLifestyleRatio(double v)  { this.lifestyleRatio = v; }

    public List<String> getStrengths()           { return strengths; }
    public void setStrengths(List<String> v)     { this.strengths = v; }

    public List<String> getRisks()               { return risks; }
    public void setRisks(List<String> v)         { this.risks = v; }

    public List<String> getRecommendedActions()          { return recommendedActions; }
    public void setRecommendedActions(List<String> v)    { this.recommendedActions = v; }

    public int getTotalTransactionsAnalyzed()        { return totalTransactionsAnalyzed; }
    public void setTotalTransactionsAnalyzed(int v)  { this.totalTransactionsAnalyzed = v; }

    public LocalDate getProfileGeneratedAt()         { return profileGeneratedAt; }
    public void setProfileGeneratedAt(LocalDate v)   { this.profileGeneratedAt = v; }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters & Setters — Tier-1 personalisation fields
    // ─────────────────────────────────────────────────────────────────────────

    public List<RecurringExpenseDetector.RecurringExpense> getRecurringExpenses() {
        return recurringExpenses;
    }
    public void setRecurringExpenses(List<RecurringExpenseDetector.RecurringExpense> v) {
        this.recurringExpenses = v;
    }

    public SalaryRunwayAnalyzer.RunwayResult getSalaryRunway() { return salaryRunway; }
    public void setSalaryRunway(SalaryRunwayAnalyzer.RunwayResult v) { this.salaryRunway = v; }

    public WeeklySpendingVelocityAnalyzer.VelocityResult getWeeklyVelocity() { return weeklyVelocity; }
    public void setWeeklyVelocity(WeeklySpendingVelocityAnalyzer.VelocityResult v) { this.weeklyVelocity = v; }

    public DeclaredVsActualAnalyzer.DiscrepancyReport getDiscrepancyReport() { return discrepancyReport; }
    public void setDiscrepancyReport(DeclaredVsActualAnalyzer.DiscrepancyReport v) { this.discrepancyReport = v; }

    // ─────────────────────────────────────────────────────────────────────────
    // AI Copilot context serialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serializes the complete profile into a structured context string
     * for injection into the AI Copilot system prompt.
     *
     * Includes base profile + all Tier-1 personalisation analytics.
     * The model can reference any of these facts when answering user questions.
     */
    public String toAiContext() {
        StringBuilder sb = new StringBuilder();

        sb.append("=== USER FINANCIAL PROFILE ===\n");
        sb.append(String.format("Name      : %s%n", fullName));
        sb.append(String.format("Archetype : %s%n", archetype));
        sb.append(String.format("Health Score: %d/100%n%n", healthScore));

        // ── Sub-scores ────────────────────────────────────────────────────
        sb.append("SUB-SCORES:\n");
        sb.append(String.format("  Savings Rate             : %d/100%n", savingsRateScore));
        sb.append(String.format("  Spending Discipline      : %d/100%n", spendingDisciplineScore));
        sb.append(String.format("  Debt Burden (higher=better): %d/100%n", debtBurdenScore));
        sb.append(String.format("  Income Stability         : %d/100%n", incomeStabilityScore));
        sb.append(String.format("  Emergency Cushion        : %d/100%n", emergencyCushionScore));
        sb.append(String.format("  Goal Alignment           : %d/100%n%n", goalAlignmentScore));

        // ── Financial snapshot ────────────────────────────────────────────
        sb.append("FINANCIAL SNAPSHOT:\n");
        sb.append(String.format("  Monthly Income     : ₹%s%n",
                monthlyIncome    != null ? monthlyIncome.toPlainString()    : "N/A"));
        sb.append(String.format("  Monthly Savings    : ₹%s%n",
                monthlySavings   != null ? monthlySavings.toPlainString()   : "N/A"));
        sb.append(String.format("  Avg Monthly Spend  : ₹%s%n",
                avgMonthlySpend  != null ? avgMonthlySpend.toPlainString()  : "N/A"));
        sb.append(String.format("  Lifestyle Ratio    : %.1f%% of total spend%n", lifestyleRatio));
        sb.append(String.format("  Occupation         : %s%n%n",
                occupation != null ? occupation : "N/A"));

        // ── Goal ──────────────────────────────────────────────────────────
        sb.append("GOAL:\n");
        sb.append(String.format("  Goal           : %s%n",
                financialGoal != null ? financialGoal : "Not set"));
        sb.append(String.format("  Target Amount  : ₹%s%n",
                goalAmount != null ? goalAmount.toPlainString() : "N/A"));
        sb.append(String.format("  Deadline       : %s%n%n",
                goalDeadline != null ? goalDeadline.toString() : "Not set"));

        // ── Strengths / risks / actions ───────────────────────────────────
        sb.append(String.format("KEY STRENGTHS: %s%n",
                strengths != null ? String.join("; ", strengths) : "None identified"));
        sb.append(String.format("KEY RISKS: %s%n",
                risks != null ? String.join("; ", risks) : "None identified"));
        sb.append(String.format("RECOMMENDED ACTIONS: %s%n%n",
                recommendedActions != null ? String.join("; ", recommendedActions) : "None"));

        // ── Tier-1 personalisation ────────────────────────────────────────
        sb.append(buildTier1Context());

        // ── Meta ──────────────────────────────────────────────────────────
        sb.append(String.format("%nTransactions analyzed : %d%n", totalTransactionsAnalyzed));
        sb.append(String.format("Profile date          : %s%n",
                profileGeneratedAt != null ? profileGeneratedAt.toString() : "N/A"));
        sb.append("===\n");



        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tier-1 context builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the Tier-1 personalisation section of the AI context string.
     * Each block is only appended if the relevant data is available.
     */
    private String buildTier1Context() {
        StringBuilder sb = new StringBuilder();

        // ── Salary runway ─────────────────────────────────────────────────
        if (salaryRunway != null && salaryRunway.insight != null) {
            sb.append("SALARY RUNWAY:\n");
            sb.append("  ").append(salaryRunway.insight).append("\n");
            if (salaryRunway.firstWeekSpend != null) {
                sb.append(String.format("  First-week spend (days 1–7)  : ₹%s%n",
                        fmt(salaryRunway.firstWeekSpend)));
            }
            if (salaryRunway.lastWeekSpend != null) {
                sb.append(String.format("  Last-week spend  (days 22–31): ₹%s%n",
                        fmt(salaryRunway.lastWeekSpend)));
            }
            if (salaryRunway.runwayDays != null) {
                sb.append(String.format("  Days to consume 60%% of spend : %d days%n",
                        salaryRunway.runwayDays));
            }
            sb.append("\n");
        }

        // ── Weekly spending velocity ──────────────────────────────────────
        if (weeklyVelocity != null) {
            sb.append(String.format("WEEKLY SPENDING PATTERN: %s%n", weeklyVelocity.pattern));
            sb.append("  ").append(weeklyVelocity.insight).append("\n");
            sb.append(String.format("  Week 1 (days  1–7 ): ₹%s%n", fmt(weeklyVelocity.week1)));
            sb.append(String.format("  Week 2 (days  8–14): ₹%s%n", fmt(weeklyVelocity.week2)));
            sb.append(String.format("  Week 3 (days 15–21): ₹%s%n", fmt(weeklyVelocity.week3)));
            sb.append(String.format("  Week 4 (days 22–31): ₹%s%n", fmt(weeklyVelocity.week4)));
            sb.append(String.format("  Week-1 to Week-4 ratio: %.2f%n%n",
                    weeklyVelocity.week1ToWeek4Ratio));
        }

        // ── Recurring expenses ────────────────────────────────────────────
        if (recurringExpenses != null && !recurringExpenses.isEmpty()) {
            sb.append("RECURRING EXPENSES DETECTED:\n");
            recurringExpenses.stream().limit(10).forEach(r ->
                    sb.append(String.format("  - %-30s ₹%s  [%s]%n",
                            r.merchant,
                            fmt(r.totalAmount),
                            r.type))
            );
            BigDecimal totalRecurring = recurringExpenses.stream()
                    .map(r -> r.totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append(String.format("  Total recurring obligations: ₹%s/month%n%n",
                    fmt(totalRecurring)));
        }

        // ── Declared vs actual ────────────────────────────────────────────
        if (discrepancyReport != null) {
            sb.append("DECLARED vs ACTUAL CROSS-CHECK:\n");
            sb.append("  Overall : ").append(discrepancyReport.overallSummary).append("\n");
            sb.append("  Income  : ").append(discrepancyReport.incomeInsight).append("\n");
            sb.append("  Savings : ").append(discrepancyReport.savingsInsight).append("\n");
            sb.append("  Debt    : ").append(discrepancyReport.debtInsight).append("\n");
            if (discrepancyReport.actualNetCashFlow != null) {
                sb.append(String.format("  Actual net cash flow this period: ₹%s%n",
                        fmt(discrepancyReport.actualNetCashFlow)));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helper
    // ─────────────────────────────────────────────────────────────────────────

    private String fmt(BigDecimal amount) {
        if (amount == null) return "N/A";
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}