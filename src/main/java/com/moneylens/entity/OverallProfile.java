package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Aggregated financial profile across all uploaded statement months.
 *
 * One row per user. Created on first statement upload, recomputed
 * after every subsequent upload by OverallProfileService.refresh().
 *
 * This is what the AI copilot uses as its primary context —
 * it represents the user's full financial picture, not just one month.
 *
 * Fields fall into 4 groups:
 *   1. Aggregate scores    — averages + trend across all months
 *   2. Aggregate financials — avg income / spend / savings
 *   3. Trend analysis      — improving / declining / stable
 *   4. Cross-month signals — confirmed recurring, best/worst month
 */
@Entity
@Table(name = "overall_profiles", indexes = {
        @Index(
                name     = "idx_overall_profile_user",
                columnList = "user_id",
                unique   = true   // one overall profile per user
        )
})
public class OverallProfile {

    public enum Trend {
        IMPROVING,   // score increased over last 3 months
        DECLINING,   // score decreased over last 3 months
        STABLE,      // less than 5-point change either way
        INSUFFICIENT_DATA  // fewer than 2 months uploaded
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // ── Coverage ──────────────────────────────────────────────────────────────

    @Column(name = "months_analyzed", nullable = false)
    private int monthsAnalyzed;         // total monthly profiles available

    @Column(name = "earliest_month")
    private LocalDate earliestMonth;    // first day of oldest month analyzed

    @Column(name = "latest_month")
    private LocalDate latestMonth;      // first day of most recent month analyzed

    // ── Aggregate scores ──────────────────────────────────────────────────────

    @Column(name = "avg_health_score")
    private int avgHealthScore;

    @Column(name = "latest_health_score")
    private int latestHealthScore;      // most recent month's score

    @Column(name = "best_health_score")
    private int bestHealthScore;

    @Column(name = "worst_health_score")
    private int worstHealthScore;

    // Sub-score averages
    @Column(name = "avg_savings_rate_score")
    private int avgSavingsRateScore;

    @Column(name = "avg_spending_discipline_score")
    private int avgSpendingDisciplineScore;

    @Column(name = "avg_debt_burden_score")
    private int avgDebtBurdenScore;

    @Column(name = "avg_income_stability_score")
    private int avgIncomeStabilityScore;

    @Column(name = "avg_emergency_cushion_score")
    private int avgEmergencyCushionScore;

    @Column(name = "avg_goal_alignment_score")
    private int avgGoalAlignmentScore;

    // ── Trend ─────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "trend", length = 20)
    private Trend trend;

    /**
     * Score change over last 3 months (positive = improving).
     * e.g. +14 means score went up 14 points in 3 months.
     */
    @Column(name = "trend_delta")
    private int trendDelta;

    // ── Aggregate financials ──────────────────────────────────────────────────

    @Column(name = "avg_income", precision = 15, scale = 2)
    private BigDecimal avgIncome;

    @Column(name = "avg_total_spend", precision = 15, scale = 2)
    private BigDecimal avgTotalSpend;

    @Column(name = "avg_actual_savings", precision = 15, scale = 2)
    private BigDecimal avgActualSavings;

    @Column(name = "avg_lifestyle_ratio")
    private double avgLifestyleRatio;

    // ── Income consistency ────────────────────────────────────────────────────

    /**
     * Fraction of months that had a detectable salary credit.
     * 1.0 = salary detected every month (high consistency).
     * 0.5 = salary only seen in half the months.
     */
    @Column(name = "income_consistency")
    private double incomeConsistency;

    // ── Best / worst months ───────────────────────────────────────────────────

    @Column(name = "best_month")
    private LocalDate bestMonth;        // first day of best-scoring month

    @Column(name = "worst_month")
    private LocalDate worstMonth;       // first day of worst-scoring month

    // ── Archetype ─────────────────────────────────────────────────────────────

    /**
     * Archetype derived from the overall (not latest) health score.
     * Reflects the user's general pattern, not a single month spike.
     */
    @Column(name = "archetype", length = 50)
    private String archetype;

    // ── Recurring burden ──────────────────────────────────────────────────────

    /**
     * Total confirmed monthly recurring obligations (EMI + subscriptions + rent).
     * Computed from RecurringPayment rows with CONFIRMED confidence.
     */
    @Column(name = "confirmed_recurring_total", precision = 15, scale = 2)
    private BigDecimal confirmedRecurringTotal;

    @Column(name = "confirmed_recurring_count")
    private int confirmedRecurringCount;

    /**
     * Count of recurring items that were NOT declared in the assessment.
     * Non-zero value = user has hidden fixed costs.
     */
    @Column(name = "undeclared_recurring_count")
    private int undeclaredRecurringCount;

    // ── Monthly score history (stored as JSON string) ─────────────────────────
    // Format: [{"month":"2026-01","score":58},{"month":"2026-02","score":54},...]
    // Kept as JSON to avoid a separate join table for a simple list.
    // The frontend uses this to render the score trend chart.

    @Column(name = "monthly_score_history", columnDefinition = "TEXT")
    private String monthlyScoreHistory;

    // ── Timestamps ────────────────────────────────────────────────────────────

    // ── Full profile JSON ─────────────────────────────────────────────────────
    // Stores the complete OverallProfileJson serialized to JSON string.
    // Includes category trends, biggest improvements/weaknesses,
    // overall strengths/risks/actions, confirmed recurring list.
    // Rebuilt by OverallProfileService.refresh() after every upload.
    @Column(name = "profile_json", columnDefinition = "TEXT")
    private String profileJson;

    @Column(name = "last_refreshed_at", nullable = false)
    private LocalDateTime lastRefreshedAt = LocalDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId()                                  { return id; }

    public User getUser()                                { return user; }
    public void setUser(User v)                          { this.user = v; }

    public int getMonthsAnalyzed()                       { return monthsAnalyzed; }
    public void setMonthsAnalyzed(int v)                 { this.monthsAnalyzed = v; }

    public LocalDate getEarliestMonth()                  { return earliestMonth; }
    public void setEarliestMonth(LocalDate v)            { this.earliestMonth = v; }

    public LocalDate getLatestMonth()                    { return latestMonth; }
    public void setLatestMonth(LocalDate v)              { this.latestMonth = v; }

    public int getAvgHealthScore()                       { return avgHealthScore; }
    public void setAvgHealthScore(int v)                 { this.avgHealthScore = v; }

    public int getLatestHealthScore()                    { return latestHealthScore; }
    public void setLatestHealthScore(int v)              { this.latestHealthScore = v; }

    public int getBestHealthScore()                      { return bestHealthScore; }
    public void setBestHealthScore(int v)                { this.bestHealthScore = v; }

    public int getWorstHealthScore()                     { return worstHealthScore; }
    public void setWorstHealthScore(int v)               { this.worstHealthScore = v; }

    public int getAvgSavingsRateScore()                  { return avgSavingsRateScore; }
    public void setAvgSavingsRateScore(int v)            { this.avgSavingsRateScore = v; }

    public int getAvgSpendingDisciplineScore()           { return avgSpendingDisciplineScore; }
    public void setAvgSpendingDisciplineScore(int v)     { this.avgSpendingDisciplineScore = v; }

    public int getAvgDebtBurdenScore()                   { return avgDebtBurdenScore; }
    public void setAvgDebtBurdenScore(int v)             { this.avgDebtBurdenScore = v; }

    public int getAvgIncomeStabilityScore()              { return avgIncomeStabilityScore; }
    public void setAvgIncomeStabilityScore(int v)        { this.avgIncomeStabilityScore = v; }

    public int getAvgEmergencyCushionScore()             { return avgEmergencyCushionScore; }
    public void setAvgEmergencyCushionScore(int v)       { this.avgEmergencyCushionScore = v; }

    public int getAvgGoalAlignmentScore()                { return avgGoalAlignmentScore; }
    public void setAvgGoalAlignmentScore(int v)          { this.avgGoalAlignmentScore = v; }

    public Trend getTrend()                              { return trend; }
    public void setTrend(Trend v)                        { this.trend = v; }

    public int getTrendDelta()                           { return trendDelta; }
    public void setTrendDelta(int v)                     { this.trendDelta = v; }

    public BigDecimal getAvgIncome()                     { return avgIncome; }
    public void setAvgIncome(BigDecimal v)               { this.avgIncome = v; }

    public BigDecimal getAvgTotalSpend()                 { return avgTotalSpend; }
    public void setAvgTotalSpend(BigDecimal v)           { this.avgTotalSpend = v; }

    public BigDecimal getAvgActualSavings()              { return avgActualSavings; }
    public void setAvgActualSavings(BigDecimal v)        { this.avgActualSavings = v; }

    public double getAvgLifestyleRatio()                 { return avgLifestyleRatio; }
    public void setAvgLifestyleRatio(double v)           { this.avgLifestyleRatio = v; }

    public double getIncomeConsistency()                 { return incomeConsistency; }
    public void setIncomeConsistency(double v)           { this.incomeConsistency = v; }

    public LocalDate getBestMonth()                      { return bestMonth; }
    public void setBestMonth(LocalDate v)                { this.bestMonth = v; }

    public LocalDate getWorstMonth()                     { return worstMonth; }
    public void setWorstMonth(LocalDate v)               { this.worstMonth = v; }

    public String getArchetype()                         { return archetype; }
    public void setArchetype(String v)                   { this.archetype = v; }

    public BigDecimal getConfirmedRecurringTotal()       { return confirmedRecurringTotal; }
    public void setConfirmedRecurringTotal(BigDecimal v) { this.confirmedRecurringTotal = v; }

    public int getConfirmedRecurringCount()              { return confirmedRecurringCount; }
    public void setConfirmedRecurringCount(int v)        { this.confirmedRecurringCount = v; }

    public int getUndeclaredRecurringCount()             { return undeclaredRecurringCount; }
    public void setUndeclaredRecurringCount(int v)       { this.undeclaredRecurringCount = v; }

    public String getMonthlyScoreHistory()               { return monthlyScoreHistory; }
    public void setMonthlyScoreHistory(String v)         { this.monthlyScoreHistory = v; }

    public String getProfileJson()                       { return profileJson; }
    public void setProfileJson(String v)                 { this.profileJson = v; }

    public LocalDateTime getLastRefreshedAt()            { return lastRefreshedAt; }
    public void setLastRefreshedAt(LocalDateTime v)      { this.lastRefreshedAt = v; }

    public LocalDateTime getCreatedAt()                  { return createdAt; }
}