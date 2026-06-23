package com.moneylens.dto.profile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Complete aggregated financial profile across all uploaded months.
 * Serialized to JSON and stored in OverallProfile.profileJson.
 *
 * This is what the copilot always gets as the "big picture" context.
 * Individual MonthlyProfileJson is only added when user asks about
 * a specific month.
 */
public class OverallProfileJson {

    // ── Identity ──────────────────────────────────────────────────────────────
    private int    monthsAnalyzed;
    private String period;          // "Jan 2025 – Oct 2025"
    private String archetype;

    // ── Scores ───────overall.─────────────────────────────────────────────────────────
    private int avgHealthScore;
    private int latestHealthScore;
    private int bestHealthScore;
    private int worstHealthScore;

    // ── Trend ─────────────────────────────────────────────────────────────────
    private String trend;           // IMPROVING / DECLINING / STABLE / INSUFFICIENT_DATA
    private int    trendDelta;      // e.g. +7

    // ── Monthly score history (for chart) ─────────────────────────────────────
    private List<MonthScore> monthlyScores;

    // ── Avg sub-scores ────────────────────────────────────────────────────────
    private AvgSubScores avgSubScores;

    // ── Avg financials ────────────────────────────────────────────────────────
    private AvgFinancials avgFinancials;

    // ── Confirmed recurring ───────────────────────────────────────────────────
    private List<ConfirmedRecurring> confirmedRecurring;
    private BigDecimal confirmedRecurringTotal;
    private int        confirmedRecurringCount;
    private int        undeclaredRecurringCount;

    // ── Category spend trend ──────────────────────────────────────────────────
    // category → list of monthly totals in chronological order
    // e.g. "Food & Dining" → [8200, 7800, 9100, ...]
    private Map<String, List<BigDecimal>> spendingTrendByCategory;

    // ── Sub-score trends ──────────────────────────────────────────────────────
    private List<String> biggestImprovements;  // e.g. "Spending Discipline (+18 pts)"
    private List<String> biggestWeaknesses;    // e.g. "Emergency Cushion (avg 10/100)"

    // ── Best / worst months ───────────────────────────────────────────────────
    private String bestMonth;   // "2025-05"
    private String worstMonth;  // "2025-02"

    // ── Overall insights ──────────────────────────────────────────────────────
    private List<String> overallStrengths;
    private List<String> overallRisks;
    private List<String> overallActions;

    // ─────────────────────────────────────────────────────────────────────────
    // Nested classes
    // ─────────────────────────────────────────────────────────────────────────

    public static class MonthScore {
        public String month;   // "2025-01"
        public int    score;

        public MonthScore(String month, int score) {
            this.month = month;
            this.score = score;
        }
    }

    public static class AvgSubScores {
        public int savingsRate;
        public int spendingDiscipline;
        public int debtBurden;
        public int incomeStability;
        public int emergencyCushion;
        public int goalAlignment;
    }

    public static class AvgFinancials {
        public BigDecimal avgIncome;
        public BigDecimal avgSpend;
        public BigDecimal avgSavings;
        public BigDecimal avgTotalCredits;
        public BigDecimal avgNetStatementFlow;
        public double     avgLifestyleRatio;
        public double     incomeConsistency;
    }

    public static class ConfirmedRecurring {
        public String     merchant;
        public BigDecimal amount;
        public String     type;
        public int        monthsDetected;
        public boolean    declaredInAssessment;

        public ConfirmedRecurring(String merchant, BigDecimal amount,
                                  String type, int monthsDetected,
                                  boolean declared) {
            this.merchant            = merchant;
            this.amount              = amount;
            this.type                = type;
            this.monthsDetected      = monthsDetected;
            this.declaredInAssessment = declared;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters & Setters
    // ─────────────────────────────────────────────────────────────────────────

    public int getMonthsAnalyzed()                               { return monthsAnalyzed; }
    public void setMonthsAnalyzed(int v)                         { this.monthsAnalyzed = v; }

    public String getPeriod()                                    { return period; }
    public void setPeriod(String v)                              { this.period = v; }

    public String getArchetype()                                 { return archetype; }
    public void setArchetype(String v)                           { this.archetype = v; }

    public int getAvgHealthScore()                               { return avgHealthScore; }
    public void setAvgHealthScore(int v)                         { this.avgHealthScore = v; }

    public int getLatestHealthScore()                            { return latestHealthScore; }
    public void setLatestHealthScore(int v)                      { this.latestHealthScore = v; }

    public int getBestHealthScore()                              { return bestHealthScore; }
    public void setBestHealthScore(int v)                        { this.bestHealthScore = v; }

    public int getWorstHealthScore()                             { return worstHealthScore; }
    public void setWorstHealthScore(int v)                       { this.worstHealthScore = v; }

    public String getTrend()                                     { return trend; }
    public void setTrend(String v)                               { this.trend = v; }

    public int getTrendDelta()                                   { return trendDelta; }
    public void setTrendDelta(int v)                             { this.trendDelta = v; }

    public List<MonthScore> getMonthlyScores()                   { return monthlyScores; }
    public void setMonthlyScores(List<MonthScore> v)             { this.monthlyScores = v; }

    public AvgSubScores getAvgSubScores()                        { return avgSubScores; }
    public void setAvgSubScores(AvgSubScores v)                  { this.avgSubScores = v; }

    public AvgFinancials getAvgFinancials()                      { return avgFinancials; }
    public void setAvgFinancials(AvgFinancials v)                { this.avgFinancials = v; }

    public List<ConfirmedRecurring> getConfirmedRecurring()      { return confirmedRecurring; }
    public void setConfirmedRecurring(List<ConfirmedRecurring> v){ this.confirmedRecurring = v; }

    public BigDecimal getConfirmedRecurringTotal()               { return confirmedRecurringTotal; }
    public void setConfirmedRecurringTotal(BigDecimal v)         { this.confirmedRecurringTotal = v; }

    public int getConfirmedRecurringCount()                      { return confirmedRecurringCount; }
    public void setConfirmedRecurringCount(int v)                { this.confirmedRecurringCount = v; }

    public int getUndeclaredRecurringCount()                     { return undeclaredRecurringCount; }
    public void setUndeclaredRecurringCount(int v)               { this.undeclaredRecurringCount = v; }

    public Map<String, List<BigDecimal>> getSpendingTrendByCategory() { return spendingTrendByCategory; }
    public void setSpendingTrendByCategory(Map<String, List<BigDecimal>> v) { this.spendingTrendByCategory = v; }

    public List<String> getBiggestImprovements()                 { return biggestImprovements; }
    public void setBiggestImprovements(List<String> v)           { this.biggestImprovements = v; }

    public List<String> getBiggestWeaknesses()                   { return biggestWeaknesses; }
    public void setBiggestWeaknesses(List<String> v)             { this.biggestWeaknesses = v; }

    public String getBestMonth()                                 { return bestMonth; }
    public void setBestMonth(String v)                           { this.bestMonth = v; }

    public String getWorstMonth()                                { return worstMonth; }
    public void setWorstMonth(String v)                          { this.worstMonth = v; }

    public List<String> getOverallStrengths()                    { return overallStrengths; }
    public void setOverallStrengths(List<String> v)              { this.overallStrengths = v; }

    public List<String> getOverallRisks()                        { return overallRisks; }
    public void setOverallRisks(List<String> v)                  { this.overallRisks = v; }

    public List<String> getOverallActions()                      { return overallActions; }
    public void setOverallActions(List<String> v)                { this.overallActions = v; }
}