package com.moneylens.dto.budget;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class BudgetJson {
    public static class BucketBreakdown {
        public BigDecimal needsTotal;
        public BigDecimal wantsTotal;
        public BigDecimal savingsTotal;
        public BigDecimal committedTotal;
        public double needsPct;
        public double wantsPct;
        public double savingsPct;
        public double committedPct;
        public SavingsBreakdown savingsBreakdown;
    }

    public static class SavingsBreakdown {
        public BigDecimal goalFund;
        public BigDecimal emergencyFund;
        public BigDecimal freeSavings;
        public String goalName;
        public double goalPct;
        public double emergencyPct;
        public double freePct;
    }


    public BigDecimal totalBudget;
    public BigDecimal savingsTarget;
    public Map<String, BigDecimal> categoryBudgets;
    public Map<String, BigDecimal> committedExpenses; // fixed monthly outflows from assessment
    public Map<String, String> reasoning;
    public String source; // AUTO | AI_REFINED | USER_ADJUSTED
    public String generatedAt;
    public BucketBreakdown bucketBreakdown;

    public static class DailyPacing {
        public BigDecimal monthlyBudget;  // discretionary only (after committed)
        public BigDecimal grossIncome;    // total income from assessment
        public BigDecimal committedTotal; // sum of committed expenses
        public BigDecimal spentSoFar;
        public BigDecimal remaining;
        public int daysRemaining;
        public BigDecimal dailyAllowance;
        public String status;
        public String insight;
        public int currentStreak;
        public boolean streakActive;
        public BigDecimal savedVsLastMonth;
        public BigDecimal spentThisWeek;
        public BigDecimal weeklyBudget;
    }

    public static class CategoryProgressEntry {
        public BigDecimal budgeted;
        public BigDecimal spent;
        public BigDecimal remaining;
        public double pct; // 0-100+, can exceed 100 if over budget
        public String status; // ON_TRACK | NEAR_LIMIT | OVER_BUDGET
    }

    public static class CategoryProgress {
        public Map<String, CategoryProgressEntry> categories;
        public String month; // "2026-06"
    }

    public static class BudgetChange {
        public String category; // or "_total" or "_savings"
        public BigDecimal oldAmount;
        public BigDecimal newAmount;
        public String reason;
    }

    public static class BudgetDiff {
        public List<BudgetChange> changes;
        public String summary; // human-readable one-liner
    }

    public static class GoalProgress {
        public boolean hasGoal;
        public String goalName;
        public BigDecimal targetAmount;
        public BigDecimal savedSoFar;       // cumulative net savings since tracking began
        public BigDecimal remaining;
        public double pct;
        public String deadline;             // "2028-06-14"
        public Integer monthsRemaining;
        public BigDecimal requiredMonthlyPace;  // to hit goal by deadline
        public BigDecimal currentMonthlyPace;   // budget's savings target
        public String paceStatus; // ON_TRACK | BEHIND | AHEAD
        public String insight;
    }

    public static class IncomeUpdateResult {
        public BigDecimal oldIncome;
        public BigDecimal newIncome;
        public BudgetJson budget;
    }

    public static class GoalUpdateResult {
        public String goalName;
        public BigDecimal oldTargetAmount;
        public BigDecimal newTargetAmount;
        public String oldDeadline;
        public String newDeadline;
        public BudgetJson budget;
    }

    public static class HomeHighlight {
        public String type;     // STREAK | OVERSPEND | NEAR_LIMIT | BUDGET_CHANGED | SAVED_MORE | NONE
        public String message;
        public String severity; // POSITIVE | WARNING | INFO
    }

    public static class BudgetOption {
        public String strategyId;       // "balanced" | "aggressive" | "comfortable"
        public String label;            // "Balanced", "Save more", "Breathing room"
        public String tagline;          // one-line description shown on the picker card
        public BigDecimal totalBudget;
        public BigDecimal savingsTarget;
        public Map<String, BigDecimal> categoryBudgets;
        public BucketBreakdown bucketBreakdown;
        public Map<String, String> reasoning;
    }

    public static class BudgetOptionsResponse {
        public List<BudgetOption> options;
        public String recommendedStrategyId;
    }

    public static class FinancialHealthSubScores {
        public int savingsRate;
        public int spendingDiscipline;
        public int debtBurden;
        public int incomeStability;
        public int emergencyCushion;
        public int goalAlignment;
    }

    public static class FinancialHealthScore {
        public int score;
        public String archetype;
        public FinancialHealthSubScores subScores;
    }

    public static class CategoryTemplate {
        public String name;
        public String bucket; // "NEEDS" or "WANTS"
        public boolean isDefault;

        public CategoryTemplate() {}
        public CategoryTemplate(String name, String bucket, boolean isDefault) {
            this.name = name;
            this.bucket = bucket;
            this.isDefault = isDefault;
        }
    }

    public static class DeclaredVsActual {
        public String     overallSummary;
        public String     savingsInsight;
        public String     debtInsight;
        public java.math.BigDecimal declaredIncome;
        public java.math.BigDecimal declaredSavings;
        public java.math.BigDecimal actualSpend;
        public java.math.BigDecimal impliedActualSavings;
        public boolean    savingsOverstated;
        public boolean    hiddenDebtFound;
        public java.math.BigDecimal estimatedMonthlyEmi;
    }

}