package com.moneylens.service;

import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionType;
import com.moneylens.entity.UserAssessment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Cross-checks declared assessment figures against actual statement spending.
 *
 * IMPORTANT DESIGN DECISION: income is NEVER inferred from statement
 * transactions. The user declares monthlyIncome (and rent) themselves in
 * the assessment — that figure is treated as ground truth everywhere in
 * this app. Bank statements are used only to measure ACTUAL SPENDING and
 * recurring obligations, never to guess at income. Earlier versions tried
 * to detect "salary-like" credits via narration keywords (NEFT CR, SALARY,
 * etc.) and flag a mismatch against declared income — this produced a
 * false "income discrepancy" on every single statement, because most
 * credits in a typical Indian bank statement are UPI peer transfers or
 * refunds, not NEFT/RTGS salary credits. That entire comparison has been
 * removed by design, not patched.
 *
 * Checks performed now:
 *   1. Declared savings  vs (declared income − actual statement spend)
 *      — i.e., "if your income is what you say it is, does your actual
 *      spending leave room for the savings you declared?"
 *   2. Declared "no debt" vs EMI obligations actually detected by
 *      RecurringExpenseDetector (NOT re-derived independently — this is
 *      the fix for hidden-EMI totals disagreeing with the RECURRING list)
 */
@Service
public class DeclaredVsActualAnalyzer {

    public static class DiscrepancyReport {

        // ── Savings (declared income − actual spend, vs declared savings) ──
        public BigDecimal declaredIncome;
        public BigDecimal declaredSavings;
        public BigDecimal actualSpend;            // total DEBIT this period
        public BigDecimal impliedActualSavings;   // declaredIncome − actualSpend
        public BigDecimal savingsGap;             // impliedActualSavings − declaredSavings
        public boolean    overstatedSavings;
        public BigDecimal actualNetCashFlow;      // declaredIncome − actualSpend (renamed back from
// declared > implied actual

        // ── Debt — sourced from RecurringExpenseDetector, not re-derived ──
        public boolean    declaredNoDebt;
        public boolean    emiDetectedInStatement;
        public BigDecimal estimatedMonthlyEmi;    // SAME number shown in RECURRING's [EMI] rows
        public boolean    hiddenDebtFound;         // declared no debt but EMIs found

        // ── Insights ────────────────────────────────────────────────────
        public String     savingsInsight;
        public String     debtInsight;
        public String     overallSummary;

        // Deprecated fields kept at default/null so any caller still
        // referencing them (e.g. older JSON DTOs) doesn't NPE — income
        // discrepancy is intentionally never set to true anymore.
        public BigDecimal actualIncome = null;
        public BigDecimal incomeGap = null;
        public double     incomeAccuracyPct = 0;
        public boolean    incomeDiscrepancyFound = false;
        public String     incomeInsight = "Income is taken from your declared assessment value and is not re-derived from statement credits.";
    }

    /**
     * Runs declared-vs-actual checks.
     *
     * @param assessment    The user's onboarding assessment (source of truth for income/rent)
     * @param transactions  All transactions for the statement period
     * @param recurringExpenses  Already-detected recurring expenses for this SAME period,
     *                            from RecurringExpenseDetector — used as the single source
     *                            of truth for EMI amounts so this report can never disagree
     *                            with what's shown in the RECURRING section.
     */
    public DiscrepancyReport analyze(UserAssessment assessment, List<Transaction> transactions,
                                     List<RecurringExpenseDetector.RecurringExpense> recurringExpenses) {
        DiscrepancyReport report = new DiscrepancyReport();

        report.declaredIncome  = assessment.getMonthlyIncome();
        report.declaredSavings = assessment.getMonthlySavings();
        report.declaredNoDebt  = Boolean.FALSE.equals(assessment.getHasDebt());

        analyzeSavings(report, transactions);
        analyzeDebt(report, recurringExpenses);
        report.overallSummary = buildOverallSummary(report);

        return report;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Savings analysis — uses DECLARED income, not statement-inferred income
    // ─────────────────────────────────────────────────────────────────────────

    private void analyzeSavings(DiscrepancyReport report, List<Transaction> transactions) {
        report.actualSpend = transactions.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (report.declaredIncome == null || report.declaredSavings == null) {
            report.savingsInsight    = "Add your monthly income and savings target in your profile to see how your actual spending compares.";
            report.overstatedSavings = false;
            return;
        }

        report.impliedActualSavings = report.declaredIncome.subtract(report.actualSpend);
        report.savingsGap           = report.impliedActualSavings.subtract(report.declaredSavings);
        report.overstatedSavings    = report.impliedActualSavings.compareTo(report.declaredSavings) < 0;

        report.savingsInsight = buildSavingsInsight(report);
    }

    private String buildSavingsInsight(DiscrepancyReport r) {
        if (!r.overstatedSavings) {
            return String.format(
                    "On pace: with declared income of ₹%s/month and ₹%s actually spent this period, "
                            + "you're saving at least your declared target of ₹%s. ✓",
                    fmt(r.declaredIncome), fmt(r.actualSpend), fmt(r.declaredSavings));
        }

        BigDecimal shortfall = r.declaredSavings.subtract(r.impliedActualSavings);

        String severity;
        if (r.declaredSavings.compareTo(BigDecimal.ZERO) > 0
                && shortfall.compareTo(r.declaredSavings.multiply(BigDecimal.valueOf(0.5))) > 0) {
            severity = "Significant";
        } else if (r.declaredSavings.compareTo(BigDecimal.ZERO) > 0
                && shortfall.compareTo(r.declaredSavings.multiply(BigDecimal.valueOf(0.2))) > 0) {
            severity = "Moderate";
        } else {
            severity = "Minor";
        }

        return String.format(
                "%s savings gap: with declared income of ₹%s/month and ₹%s spent this period, "
                        + "your actual savings room is ₹%s — short of your ₹%s/month target by ₹%s. "
                        + "Spending is outpacing your savings goal this period.",
                severity, fmt(r.declaredIncome), fmt(r.actualSpend),
                fmt(r.impliedActualSavings), fmt(r.declaredSavings), fmt(shortfall));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debt analysis — EMI total comes from RecurringExpenseDetector, period.
    // This guarantees "Hidden EMIs detected: ₹X" always equals the sum of
    // [EMI]-tagged rows in the RECURRING section — they're now literally
    // the same calculation, not two independent ones that can disagree.
    // ─────────────────────────────────────────────────────────────────────────

    private void analyzeDebt(DiscrepancyReport report,
                             List<RecurringExpenseDetector.RecurringExpense> recurringExpenses) {
        BigDecimal emiTotal = recurringExpenses.stream()
                .filter(r -> r.type == RecurringExpenseDetector.RecurringType.EMI)
                .map(r -> r.totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        report.emiDetectedInStatement = emiTotal.compareTo(BigDecimal.ZERO) > 0;
        report.estimatedMonthlyEmi    = emiTotal;
        report.hiddenDebtFound        = report.declaredNoDebt && report.emiDetectedInStatement;
        report.debtInsight            = buildDebtInsight(report);
    }

    private String buildDebtInsight(DiscrepancyReport r) {
        if (r.hiddenDebtFound) {
            return String.format(
                    "⚠ Debt discrepancy: your profile says no active debt, but ₹%s/month in EMI/loan "
                            + "obligations were detected in your recurring payments (see RECURRING below). "
                            + "These undeclared obligations are directly reducing your actual savings capacity.",
                    fmt(r.estimatedMonthlyEmi));
        }

        if (!r.declaredNoDebt && !r.emiDetectedInStatement) {
            return "You've indicated you have debt, but no recurring EMI payments were detected this period. "
                    + "Obligations may be serviced from a different account or paid in cash.";
        }

        if (r.emiDetectedInStatement && !r.declaredNoDebt) {
            return String.format(
                    "EMI/debt obligations of ₹%s/month detected, consistent with your declared debt status. ✓",
                    fmt(r.estimatedMonthlyEmi));
        }

        return "No EMI or loan repayment transactions detected. Debt-free status is consistent with your statement. ✓";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overall summary
    // ─────────────────────────────────────────────────────────────────────────

    private String buildOverallSummary(DiscrepancyReport r) {
        int issues = 0;
        if (r.overstatedSavings) issues++;
        if (r.hiddenDebtFound)   issues++;

        return switch (issues) {
            case 0 -> "Your declared figures align with actual spending this period. ✓";
            case 1 -> "One discrepancy found between your declared figures and actual spending — see details above.";
            default -> "Both your savings pace and debt obligations differ from what you declared — "
                    + "review the details below and consider updating your profile.";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helper
    // ─────────────────────────────────────────────────────────────────────────

    private String fmt(BigDecimal amount) {
        if (amount == null) return "N/A";
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}