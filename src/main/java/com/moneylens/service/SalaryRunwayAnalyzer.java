package com.moneylens.service;

import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculates the "salary runway" — how many days after the salary credit
 * the user's cumulative spending crosses a threshold, and whether a
 * post-salary spending surge occurred in the first 3 days.
 *
 * Output fields:
 *   salaryDayOfMonth  → day the salary credit hit (e.g. 3)
 *   salaryAmount      → detected salary credit amount
 *   runwayDays        → days until 60% of monthly spend was consumed
 *   firstWeekSpend    → total debits in days 1–7 post-salary
 *   lastWeekSpend     → total debits on days 22–31 of the statement month
 *   postSalarySurge   → true if days 1–3 account for >30% of all post-salary spend
 *   insight           → human-readable summary for the AI copilot
 */
@Service
public class SalaryRunwayAnalyzer {

    /** Fraction of monthly spend that must be consumed before we call it "runway exhausted". */
    private static final double RUNWAY_THRESHOLD = 0.60;

    /** If days-1-to-3 spend exceeds this fraction of total post-salary spend, it's a surge. */
    private static final double SURGE_THRESHOLD  = 0.30;

    public static class RunwayResult {
        public Integer    salaryDayOfMonth;    // null if not detected
        public BigDecimal salaryAmount;
        public Integer    runwayDays;          // null if salary not detected
        public BigDecimal firstWeekSpend;      // days 1–7 post-salary
        public BigDecimal lastWeekSpend;       // days 22–31 of statement month
        public boolean    postSalarySurge;
        public String     insight;

        /** Whether enough data was available to compute the runway. */
        public boolean isAvailable() {
            return salaryDayOfMonth != null && runwayDays != null;
        }
    }

    /**
     * Analyzes transaction list to compute salary runway metrics.
     *
     * @param transactions Full transaction list for the statement period
     * @return             RunwayResult (check isAvailable() before using numeric fields)
     */
    public RunwayResult analyze(List<Transaction> transactions) {
        RunwayResult result = new RunwayResult();

        // ── 1. Detect salary credit ───────────────────────────────────────
        Optional<Transaction> salaryTx = transactions.stream()
                .filter(t -> t.getType() == TransactionType.CREDIT)
                .filter(t -> t.getDate() != null)
                .filter(this::isSalaryCredit)
                .max(Comparator.comparing(t ->
                        t.getDepositAmount() != null ? t.getDepositAmount() : BigDecimal.ZERO));

        if (salaryTx.isEmpty()) {
            result.insight = "Salary credit not detected in this statement period. "
                    + "Income may be received via cash or a different account.";
            return result;
        }

        Transaction salary        = salaryTx.get();
        result.salaryAmount       = salary.getDepositAmount();
        result.salaryDayOfMonth   = salary.getDate().getDayOfMonth();
        LocalDate salaryDate      = salary.getDate();

        // ── 2. Compute per-day debit amounts post-salary ──────────────────
        // Key = days offset from salary date (day 1 = salary day itself)
        Map<Integer, BigDecimal> dailySpend = new TreeMap<>();

        transactions.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> t.getDate() != null && !t.getDate().isBefore(salaryDate))
                .forEach(t -> {
                    int offset = (int) ChronoUnit.DAYS.between(salaryDate, t.getDate()) + 1;
                    BigDecimal amt = t.getWithdrawalAmount() != null
                            ? t.getWithdrawalAmount() : BigDecimal.ZERO;
                    dailySpend.merge(offset, amt, BigDecimal::add);
                });

        BigDecimal totalPostSalary = dailySpend.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── 3. First-week spend (days 1–7 post salary) ───────────────────
        result.firstWeekSpend = dailySpend.entrySet().stream()
                .filter(e -> e.getKey() >= 1 && e.getKey() <= 7)
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── 4. Last-week spend (calendar days 22–31) ─────────────────────
        result.lastWeekSpend = transactions.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> t.getDate() != null && t.getDate().getDayOfMonth() >= 22)
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── 5. Post-salary surge detection (days 1–3) ────────────────────
        BigDecimal firstThreeDaysSpend = dailySpend.entrySet().stream()
                .filter(e -> e.getKey() >= 1 && e.getKey() <= 3)
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPostSalary.compareTo(BigDecimal.ZERO) > 0) {
            double surgeRatio = firstThreeDaysSpend
                    .divide(totalPostSalary, 4, RoundingMode.HALF_UP)
                    .doubleValue();
            result.postSalarySurge = surgeRatio > SURGE_THRESHOLD;
        }

        // ── 6. Runway calculation ─────────────────────────────────────────
        // Find the day offset at which cumulative spend crosses RUNWAY_THRESHOLD
        BigDecimal runwayTarget  = totalPostSalary.multiply(BigDecimal.valueOf(RUNWAY_THRESHOLD));
        BigDecimal cumulative    = BigDecimal.ZERO;
        result.runwayDays        = dailySpend.isEmpty() ? 0 : Collections.max(dailySpend.keySet());

        for (Map.Entry<Integer, BigDecimal> entry : dailySpend.entrySet()) {
            cumulative = cumulative.add(entry.getValue());
            if (cumulative.compareTo(runwayTarget) >= 0) {
                result.runwayDays = entry.getKey();
                break;
            }
        }

        // ── 7. Human-readable insight ─────────────────────────────────────
        result.insight = buildInsight(result, totalPostSalary);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isSalaryCredit(Transaction t) {
        String narration    = t.getRawNarration()     != null ? t.getRawNarration().toUpperCase()     : "";
        String counterparty = t.getCounterpartyName() != null ? t.getCounterpartyName().toUpperCase() : "";
        return narration.contains("SALARY")
                || narration.contains("SAL ")
                || narration.contains("NEFT CR")
                || narration.contains("IMPS CR")
                || narration.contains("RTGS CR")
                || counterparty.contains("SALARY")
                || counterparty.contains("PAYROLL")
                || counterparty.contains("HR ")
                || counterparty.contains("EMPLOYER");
    }

    private String buildInsight(RunwayResult r, BigDecimal totalPostSalary) {
        if (!r.isAvailable()) return "Insufficient data to compute salary runway.";

        StringBuilder sb = new StringBuilder();

        // Core runway stat
        sb.append(String.format(
                "Salary of ₹%s credited on day %d. ",
                formatAmount(r.salaryAmount), r.salaryDayOfMonth));

        sb.append(String.format(
                "60%% of monthly spending was consumed within %d day%s of salary credit. ",
                r.runwayDays, r.runwayDays == 1 ? "" : "s"));

        // Surge flag
        if (r.postSalarySurge) {
            sb.append("⚠ Post-salary spending surge detected in first 3 days — "
                    + "consider a 48-hour 'cooling period' after salary before making large purchases. ");
        }

        // Week 1 vs week 4 comparison
        if (r.firstWeekSpend != null && r.lastWeekSpend != null) {
            int cmp = r.firstWeekSpend.compareTo(r.lastWeekSpend);
            if (cmp > 0) {
                sb.append(String.format(
                        "Week-1 spend (₹%s) exceeds week-4 spend (₹%s) — classic post-salary splurge pattern.",
                        formatAmount(r.firstWeekSpend), formatAmount(r.lastWeekSpend)));
            } else if (cmp < 0) {
                sb.append(String.format(
                        "End-of-month spend (₹%s) exceeds week-1 (₹%s) — possible cash crunch or deferred bills.",
                        formatAmount(r.lastWeekSpend), formatAmount(r.firstWeekSpend)));
            } else {
                sb.append("Spending is evenly distributed across the month.");
            }
        }

        return sb.toString().trim();
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "N/A";
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}