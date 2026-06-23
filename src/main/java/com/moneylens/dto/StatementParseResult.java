package com.moneylens.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result produced by a bank statement parser.
 *
 * Uses statementFromDate + statementToDate instead of statementYear + statementMonth
 * because a single PDF can cover any duration — 1 month, 3 months, 6 months, a year.
 */
public class StatementParseResult {

    private String    bankName;
    private LocalDate statementFromDate;
    private LocalDate statementToDate;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal totalDebits;
    private BigDecimal totalCredits;
    private List<ParsedTransaction> transactions;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** How many calendar months this statement spans. Jan–Jun = 6. */
    public long getMonthsSpanned() {
        if (statementFromDate == null || statementToDate == null) return 1;
        return java.time.temporal.ChronoUnit.MONTHS.between(
                statementFromDate.withDayOfMonth(1),
                statementToDate.withDayOfMonth(1)) + 1;
    }

    public boolean isMultiMonth() {
        return getMonthsSpanned() > 1;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getBankName()                    { return bankName; }
    public void setBankName(String v)              { this.bankName = v; }

    public LocalDate getStatementFromDate()        { return statementFromDate; }
    public void setStatementFromDate(LocalDate v)  { this.statementFromDate = v; }

    public LocalDate getStatementToDate()          { return statementToDate; }
    public void setStatementToDate(LocalDate v)    { this.statementToDate = v; }

    public BigDecimal getOpeningBalance()          { return openingBalance; }
    public void setOpeningBalance(BigDecimal v)    { this.openingBalance = v; }

    public BigDecimal getClosingBalance()          { return closingBalance; }
    public void setClosingBalance(BigDecimal v)    { this.closingBalance = v; }

    public BigDecimal getTotalDebits()             { return totalDebits; }
    public void setTotalDebits(BigDecimal v)       { this.totalDebits = v; }

    public BigDecimal getTotalCredits()            { return totalCredits; }
    public void setTotalCredits(BigDecimal v)      { this.totalCredits = v; }

    public List<ParsedTransaction> getTransactions()       { return transactions; }
    public void setTransactions(List<ParsedTransaction> v) { this.transactions = v; }
}