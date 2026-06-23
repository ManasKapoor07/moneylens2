package com.moneylens.service;

import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Breaks a month's spending into 4 calendar weeks and computes
 * per-week spend totals, the dominant pattern, and a velocity ratio.
 *
 * Week definitions (by day-of-month):
 *   Week 1 → days  1–7
 *   Week 2 → days  8–14
 *   Week 3 → days 15–21
 *   Week 4 → days 22–31
 *
 * Pattern codes:
 *   FRONT_HEAVY  → Week 1 is 1.5× or more than Week 4 (post-salary splurge)
 *   BACK_HEAVY   → Week 4 is 1.5× or more than Week 1 (end-of-month crunch / deferred spend)
 *   SPIKE_WEEK2  → Week 2 is the single highest-spend week
 *   SPIKE_WEEK3  → Week 3 is the single highest-spend week
 *   STEADY       → No week dominates; reasonably balanced
 */
@Service
public class WeeklySpendingVelocityAnalyzer {

    public static class VelocityResult {
        public BigDecimal week1;                // days  1–7
        public BigDecimal week2;                // days  8–14
        public BigDecimal week3;                // days 15–21
        public BigDecimal week4;                // days 22–31
        public double     week1ToWeek4Ratio;    // >1.5 = front-heavy; <0.67 = back-heavy
        public BigDecimal peakWeekAmount;       // highest single-week spend
        public int        peakWeekNumber;       // 1–4
        public String     pattern;              // FRONT_HEAVY | BACK_HEAVY | SPIKE_WEEK2 | SPIKE_WEEK3 | STEADY
        public String     insight;              // human-readable summary for copilot
    }

    /**
     * Analyzes debit transactions to compute weekly velocity metrics.
     *
     * @param transactions Full transaction list for the period
     * @return             VelocityResult with week totals, pattern, and insight
     */
    public VelocityResult analyze(List<Transaction> transactions) {
        VelocityResult result = new VelocityResult();

        result.week1 = sumForDayRange(transactions, 1,  7);
        result.week2 = sumForDayRange(transactions, 8,  14);
        result.week3 = sumForDayRange(transactions, 15, 21);
        result.week4 = sumForDayRange(transactions, 22, 31);

        // ── Week 1 : Week 4 ratio ─────────────────────────────────────────
        if (result.week4.compareTo(BigDecimal.ZERO) > 0) {
            result.week1ToWeek4Ratio = result.week1
                    .divide(result.week4, 4, RoundingMode.HALF_UP)
                    .doubleValue();
        } else {
            // Week 4 is zero — if week 1 has spend, ratio is effectively infinite
            result.week1ToWeek4Ratio = result.week1.compareTo(BigDecimal.ZERO) > 0 ? 999.0 : 1.0;
        }

        // ── Peak week ─────────────────────────────────────────────────────
        BigDecimal[] weeks = { result.week1, result.week2, result.week3, result.week4 };
        int peakIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (weeks[i].compareTo(weeks[peakIdx]) > 0) peakIdx = i;
        }
        result.peakWeekNumber = peakIdx + 1;
        result.peakWeekAmount = weeks[peakIdx];

        // ── Pattern classification ────────────────────────────────────────
        result.pattern = classifyPattern(result, weeks);

        // ── Human-readable insight ────────────────────────────────────────
        result.insight = buildInsight(result);

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private BigDecimal sumForDayRange(List<Transaction> transactions, int fromDay, int toDay) {
        return transactions.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> t.getDate() != null)
                .filter(t -> {
                    int dom = t.getDate().getDayOfMonth();
                    return dom >= fromDay && dom <= toDay;
                })
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String classifyPattern(VelocityResult r, BigDecimal[] weeks) {
        // Primary classification by week-1 to week-4 ratio
        if (r.week1ToWeek4Ratio > 1.5)  return "FRONT_HEAVY";
        if (r.week1ToWeek4Ratio < 0.67) return "BACK_HEAVY";

        // Secondary: mid-month spikes
        if (r.peakWeekNumber == 2) return "SPIKE_WEEK2";
        if (r.peakWeekNumber == 3) return "SPIKE_WEEK3";

        return "STEADY";
    }

    private String buildInsight(VelocityResult r) {
        return switch (r.pattern) {
            case "FRONT_HEAVY" -> String.format(
                    "Post-salary splurge pattern: week-1 spending (₹%s) is %.1fx higher than week-4 (₹%s). "
                            + "Setting a week-1 budget cap right after salary day could significantly improve savings.",
                    fmt(r.week1), r.week1ToWeek4Ratio, fmt(r.week4));

            case "BACK_HEAVY" -> String.format(
                    "End-of-month cash crunch pattern: week-4 spend (₹%s) significantly exceeds week-1 (₹%s). "
                            + "This may indicate deferred purchases, clustered EMIs, or running short before the next salary.",
                    fmt(r.week4), fmt(r.week1));

            case "SPIKE_WEEK2" -> String.format(
                    "Mid-month spending spike in week 2 (₹%s) — the highest week this period. "
                            + "Could be mid-month subscriptions, utility bills, or delayed purchases from week 1.",
                    fmt(r.week2));

            case "SPIKE_WEEK3" -> String.format(
                    "Week-3 spending spike detected (₹%s is the highest week). "
                            + "Review what drives spending in the third week — consider aligning EMI dates differently.",
                    fmt(r.week3));

            default -> String.format(
                    "Spending is relatively balanced across the month. "
                            + "Week 1: ₹%s | Week 2: ₹%s | Week 3: ₹%s | Week 4: ₹%s.",
                    fmt(r.week1), fmt(r.week2), fmt(r.week3), fmt(r.week4));
        };
    }

    private String fmt(BigDecimal amount) {
        if (amount == null) return "0";
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}