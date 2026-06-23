package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * Stores the computed financial profile for a single calendar month.
 *
 * One StatementProfile is created per calendar month found in an uploaded
 * statement. A 6-month PDF produces 6 StatementProfile rows, all linked
 * to the same BankStatement.
 *
 * Lifecycle:
 *   Created by StatementProfileService.buildAndSave() during upload.
 *   Never updated after creation — if re-analysis is needed, the row
 *   is deleted and rebuilt.
 *
 * What it stores:
 *   - The 6 sub-scores + composite health score for that month
 *   - Actual income / spend / savings derived from transactions
 *   - Salary day, runway days, weekly pattern
 *   - Declared-vs-actual summary flags
 *   - Archetype for that month
 *
 * What it does NOT store:
 *   - Full transaction list (those are in the transactions table)
 *   - Recurring payments (those are in recurring_payments table, Step 4)
 *   - Raw profile JSON (kept normalized for queryability)
 */
@Entity
@Table(name = "statement_profiles", indexes = {
        @Index(
                name = "idx_profile_user_month",
                columnList = "user_id, profile_year, profile_month",
                unique = true   // one profile per user per calendar month
        ),
        @Index(
                name = "idx_profile_statement",
                columnList = "statement_id"
        )
})
public class StatementProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Relationships ─────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id", nullable = false)
    private BankStatement statement;

    // ── Which calendar month this profile covers ───────────────────────────────

    @Column(name = "profile_year", nullable = false)
    private int profileYear;      // e.g. 2026

    @Column(name = "profile_month", nullable = false)
    private int profileMonth;     // e.g. 5 (May)

    // ── Composite score ───────────────────────────────────────────────────────

    @Column(name = "health_score", nullable = false)
    private int healthScore;      // 0–100

    @Column(name = "archetype", length = 50)
    private String archetype;     // e.g. "The Aspirational Improver"

    // ── Sub-scores ────────────────────────────────────────────────────────────

    @Column(name = "savings_rate_score")
    private int savingsRateScore;

    @Column(name = "spending_discipline_score")
    private int spendingDisciplineScore;

    @Column(name = "debt_burden_score")
    private int debtBurdenScore;

    @Column(name = "income_stability_score")
    private int incomeStabilityScore;

    @Column(name = "emergency_cushion_score")
    private int emergencyCushionScore;

    @Column(name = "goal_alignment_score")
    private int goalAlignmentScore;

    // ── Actual financials for this month (from transactions) ──────────────────

    @Column(name = "total_credits", precision = 15, scale = 2)
    private BigDecimal totalCredits;       // sum of all credits from statement

    @Column(name = "actual_income", precision = 15, scale = 2)
    private BigDecimal actualIncome;       // kept for backwards compat, no longer populated

    @Column(name = "total_spend", precision = 15, scale = 2)
    private BigDecimal totalSpend;         // sum of all debits

    @Column(name = "actual_savings", precision = 15, scale = 2)
    private BigDecimal actualSavings;      // kept for backwards compat, no longer populated

    @Column(name = "avg_daily_spend", precision = 15, scale = 2)
    private BigDecimal avgDailySpend;

    @Column(name = "lifestyle_ratio")
    private double lifestyleRatio;         // discretionary spend %

    // ── Salary runway ─────────────────────────────────────────────────────────

    @Column(name = "salary_day")
    private Integer salaryDay;             // day of month salary credited

    @Column(name = "runway_days")
    private Integer runwayDays;            // days to consume 60% of spend

    @Column(name = "post_salary_surge")
    private boolean postSalarySurge;       // heavy spend in first 3 days

    // ── Weekly pattern ────────────────────────────────────────────────────────

    @Column(name = "weekly_pattern", length = 20)
    private String weeklyPattern;          // FRONT_HEAVY / BACK_HEAVY / STEADY etc.

    @Column(name = "week1_spend", precision = 15, scale = 2)
    private BigDecimal week1Spend;

    @Column(name = "week2_spend", precision = 15, scale = 2)
    private BigDecimal week2Spend;

    @Column(name = "week3_spend", precision = 15, scale = 2)
    private BigDecimal week3Spend;

    @Column(name = "week4_spend", precision = 15, scale = 2)
    private BigDecimal week4Spend;

    // ── Declared vs actual flags ──────────────────────────────────────────────

    @Column(name = "income_discrepancy_found")
    private boolean incomeDiscrepancyFound;

    @Column(name = "savings_overstated")
    private boolean savingsOverstated;

    @Column(name = "hidden_debt_found")
    private boolean hiddenDebtFound;

    @Column(name = "actual_net_cash_flow", precision = 15, scale = 2)
    private BigDecimal actualNetCashFlow;  // credits - debits for the month

    // ── Transaction count ─────────────────────────────────────────────────────

    @Column(name = "transaction_count")
    private int transactionCount;

    // ── Timestamps ────────────────────────────────────────────────────────────

    // ── Full profile JSON ─────────────────────────────────────────────────────
    // Stores the complete MonthlyProfileJson serialized to JSON string.
    // Includes spending breakdown, top merchants, strengths, risks, actions,
    // recurring payments, declared-vs-actual insights, runway + weekly insights.
    // Kept in sync with StatementProfileService every time a profile is built.
    @Column(name = "profile_json", columnDefinition = "TEXT")
    private String profileJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Convenience ───────────────────────────────────────────────────────────

    public YearMonth getYearMonth() {
        return YearMonth.of(profileYear, profileMonth);
    }

    public LocalDate getFirstDayOfMonth() {
        return LocalDate.of(profileYear, profileMonth, 1);
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId()                              { return id; }

    public User getUser()                            { return user; }
    public void setUser(User v)                      { this.user = v; }

    public BankStatement getStatement()              { return statement; }
    public void setStatement(BankStatement v)        { this.statement = v; }

    public int getProfileYear()                      { return profileYear; }
    public void setProfileYear(int v)                { this.profileYear = v; }

    public int getProfileMonth()                     { return profileMonth; }
    public void setProfileMonth(int v)               { this.profileMonth = v; }

    public int getHealthScore()                      { return healthScore; }
    public void setHealthScore(int v)                { this.healthScore = v; }

    public String getArchetype()                     { return archetype; }
    public void setArchetype(String v)               { this.archetype = v; }

    public int getSavingsRateScore()                 { return savingsRateScore; }
    public void setSavingsRateScore(int v)           { this.savingsRateScore = v; }

    public int getSpendingDisciplineScore()          { return spendingDisciplineScore; }
    public void setSpendingDisciplineScore(int v)    { this.spendingDisciplineScore = v; }

    public int getDebtBurdenScore()                  { return debtBurdenScore; }
    public void setDebtBurdenScore(int v)            { this.debtBurdenScore = v; }

    public int getIncomeStabilityScore()             { return incomeStabilityScore; }
    public void setIncomeStabilityScore(int v)       { this.incomeStabilityScore = v; }

    public int getEmergencyCushionScore()            { return emergencyCushionScore; }
    public void setEmergencyCushionScore(int v)      { this.emergencyCushionScore = v; }

    public int getGoalAlignmentScore()               { return goalAlignmentScore; }
    public void setGoalAlignmentScore(int v)         { this.goalAlignmentScore = v; }

    public BigDecimal getTotalCredits()              { return totalCredits; }
    public void setTotalCredits(BigDecimal v)        { this.totalCredits = v; }

    public BigDecimal getActualIncome()              { return actualIncome; }
    public void setActualIncome(BigDecimal v)        { this.actualIncome = v; }

    public BigDecimal getTotalSpend()                { return totalSpend; }
    public void setTotalSpend(BigDecimal v)          { this.totalSpend = v; }

    public BigDecimal getActualSavings()             { return actualSavings; }
    public void setActualSavings(BigDecimal v)       { this.actualSavings = v; }

    public BigDecimal getAvgDailySpend()             { return avgDailySpend; }
    public void setAvgDailySpend(BigDecimal v)       { this.avgDailySpend = v; }

    public double getLifestyleRatio()                { return lifestyleRatio; }
    public void setLifestyleRatio(double v)          { this.lifestyleRatio = v; }

    public Integer getSalaryDay()                    { return salaryDay; }
    public void setSalaryDay(Integer v)              { this.salaryDay = v; }

    public Integer getRunwayDays()                   { return runwayDays; }
    public void setRunwayDays(Integer v)             { this.runwayDays = v; }

    public boolean isPostSalarySurge()               { return postSalarySurge; }
    public void setPostSalarySurge(boolean v)        { this.postSalarySurge = v; }

    public String getWeeklyPattern()                 { return weeklyPattern; }
    public void setWeeklyPattern(String v)           { this.weeklyPattern = v; }

    public BigDecimal getWeek1Spend()                { return week1Spend; }
    public void setWeek1Spend(BigDecimal v)          { this.week1Spend = v; }

    public BigDecimal getWeek2Spend()                { return week2Spend; }
    public void setWeek2Spend(BigDecimal v)          { this.week2Spend = v; }

    public BigDecimal getWeek3Spend()                { return week3Spend; }
    public void setWeek3Spend(BigDecimal v)          { this.week3Spend = v; }

    public BigDecimal getWeek4Spend()                { return week4Spend; }
    public void setWeek4Spend(BigDecimal v)          { this.week4Spend = v; }

    public boolean isIncomeDiscrepancyFound()        { return incomeDiscrepancyFound; }
    public void setIncomeDiscrepancyFound(boolean v) { this.incomeDiscrepancyFound = v; }

    public boolean isSavingsOverstated()             { return savingsOverstated; }
    public void setSavingsOverstated(boolean v)      { this.savingsOverstated = v; }

    public boolean isHiddenDebtFound()               { return hiddenDebtFound; }
    public void setHiddenDebtFound(boolean v)        { this.hiddenDebtFound = v; }

    public BigDecimal getActualNetCashFlow()         { return actualNetCashFlow; }
    public void setActualNetCashFlow(BigDecimal v)   { this.actualNetCashFlow = v; }

    public int getTransactionCount()                 { return transactionCount; }
    public void setTransactionCount(int v)           { this.transactionCount = v; }

    public String getProfileJson()                   { return profileJson; }
    public void setProfileJson(String v)             { this.profileJson = v; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
}