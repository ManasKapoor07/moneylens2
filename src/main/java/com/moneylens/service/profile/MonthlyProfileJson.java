package com.moneylens.dto.profile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Complete financial profile for one calendar month.
 * Serialized to JSON and stored in StatementProfile.profileJson.
 *
 * This is the single source of truth for one month — everything
 * the UI and copilot need is here. No joins, no extra queries.
 */
public class MonthlyProfileJson {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String month;           // "2025-01"
    private int    healthScore;
    private String archetype;
    private int    transactionCount;

    // ── Sub-scores ────────────────────────────────────────────────────────────
    private SubScores subScores;

    // ── Financials ────────────────────────────────────────────────────────────
    private Financials financials;

    // ── Spending breakdown ────────────────────────────────────────────────────
    private Map<String, BigDecimal> spendingBreakdown;   // category → total

    // ── Top merchants ─────────────────────────────────────────────────────────
    private List<MerchantEntry> topMerchants;

    // ── Salary runway ─────────────────────────────────────────────────────────
    private SalaryRunway salaryRunway;

    // ── Weekly pattern ────────────────────────────────────────────────────────
    private WeeklyPattern weeklyPattern;

    // ── Recurring payments ────────────────────────────────────────────────────
    private List<RecurringEntry> recurringPayments;

    // ── Insights ──────────────────────────────────────────────────────────────
    private List<String> strengths;
    private List<String> risks;
    private List<String> recommendedActions;

    // ─────────────────────────────────────────────────────────────────────────
    // Nested classes
    // ─────────────────────────────────────────────────────────────────────────

    public static class SubScores {
        public int spendingDiscipline;
        public int debtServiceLoad;
        public int velocityConsistency;
        public int recurringObligationLoad;
    }

    public static class Financials {
        public BigDecimal totalCredits;
        public BigDecimal totalDebits;
        public BigDecimal netStatementFlow;
        public BigDecimal avgDailySpend;
        public double     lifestyleRatio;
    }

    public static class MerchantEntry {
        public String     name;
        public BigDecimal amount;

        public MerchantEntry(String name, BigDecimal amount) {
            this.name   = name;
            this.amount = amount;
        }
    }

    public static class SalaryRunway {
        public Integer salaryDay;
        public Integer runwayDays;
        public boolean postSalarySurge;
        public String  insight;
    }

    public static class WeeklyPattern {
        public String     pattern;     // FRONT_HEAVY / BACK_HEAVY / STEADY etc.
        public BigDecimal week1;
        public BigDecimal week2;
        public BigDecimal week3;
        public BigDecimal week4;
        public double     week1ToWeek4Ratio;
        public String     insight;
    }

    public static class RecurringEntry {
        public String     merchant;
        public BigDecimal amount;
        public String     type;        // SUBSCRIPTION / EMI / RENT / REPEATED_DEBIT
        public String     confidence;  // POSSIBLE / LIKELY / CONFIRMED
        public int        monthsDetected;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters & Setters
    // ─────────────────────────────────────────────────────────────────────────

    public String getMonth()                                 { return month; }
    public void setMonth(String v)                           { this.month = v; }

    public int getHealthScore()                              { return healthScore; }
    public void setHealthScore(int v)                        { this.healthScore = v; }

    public String getArchetype()                             { return archetype; }
    public void setArchetype(String v)                       { this.archetype = v; }

    public int getTransactionCount()                         { return transactionCount; }
    public void setTransactionCount(int v)                   { this.transactionCount = v; }

    public SubScores getSubScores()                          { return subScores; }
    public void setSubScores(SubScores v)                    { this.subScores = v; }

    public Financials getFinancials()                        { return financials; }
    public void setFinancials(Financials v)                  { this.financials = v; }

    public Map<String, BigDecimal> getSpendingBreakdown()    { return spendingBreakdown; }
    public void setSpendingBreakdown(Map<String, BigDecimal> v) { this.spendingBreakdown = v; }

    public List<MerchantEntry> getTopMerchants()             { return topMerchants; }
    public void setTopMerchants(List<MerchantEntry> v)       { this.topMerchants = v; }

    public SalaryRunway getSalaryRunway()                    { return salaryRunway; }
    public void setSalaryRunway(SalaryRunway v)              { this.salaryRunway = v; }

    public WeeklyPattern getWeeklyPattern()                  { return weeklyPattern; }
    public void setWeeklyPattern(WeeklyPattern v)            { this.weeklyPattern = v; }

    public List<RecurringEntry> getRecurringPayments()       { return recurringPayments; }
    public void setRecurringPayments(List<RecurringEntry> v) { this.recurringPayments = v; }

    public List<String> getStrengths()                       { return strengths; }
    public void setStrengths(List<String> v)                 { this.strengths = v; }

    public List<String> getRisks()                           { return risks; }
    public void setRisks(List<String> v)                     { this.risks = v; }

    public List<String> getRecommendedActions()              { return recommendedActions; }
    public void setRecommendedActions(List<String> v)        { this.recommendedActions = v; }
}