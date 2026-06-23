package com.moneylens.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response returned after a successful statement upload.
 *
 * CHANGED from original:
 *   - Removed: statementMonth (int), statementYear (int)
 *   - Added:   statementFromDate, statementToDate, monthsSpanned
 *
 * Why: A single upload now covers any date range.
 * The frontend needs fromDate/toDate to display the period correctly
 * and monthsSpanned to know how many monthly profiles were created.
 */
public class StatementUploadResponse {

    private final String    bankName;
    private final LocalDate statementFromDate;
    private final LocalDate statementToDate;
    private final long      monthsSpanned;

    private final int        rawTransactionCount;
    private final int        parsedTransactionCount;
    private final int        skippedTransactionCount;

    private final BigDecimal openingBalance;
    private final BigDecimal closingBalance;
    private final BigDecimal totalDebits;
    private final BigDecimal totalCredits;

    // Number of StatementProfile rows created (one per calendar month in the range)
    private final int monthlyProfilesCreated;

    public StatementUploadResponse(
            String    bankName,
            LocalDate statementFromDate,
            LocalDate statementToDate,
            long      monthsSpanned,
            int       rawTransactionCount,
            int       parsedTransactionCount,
            int       skippedTransactionCount,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            int       monthlyProfilesCreated) {

        this.bankName                = bankName;
        this.statementFromDate       = statementFromDate;
        this.statementToDate         = statementToDate;
        this.monthsSpanned           = monthsSpanned;
        this.rawTransactionCount     = rawTransactionCount;
        this.parsedTransactionCount  = parsedTransactionCount;
        this.skippedTransactionCount = skippedTransactionCount;
        this.openingBalance          = openingBalance;
        this.closingBalance          = closingBalance;
        this.totalDebits             = totalDebits;
        this.totalCredits            = totalCredits;
        this.monthlyProfilesCreated  = monthlyProfilesCreated;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String    getBankName()                { return bankName; }
    public LocalDate getStatementFromDate()       { return statementFromDate; }
    public LocalDate getStatementToDate()         { return statementToDate; }
    public long      getMonthsSpanned()           { return monthsSpanned; }
    public int       getRawTransactionCount()     { return rawTransactionCount; }
    public int       getParsedTransactionCount()  { return parsedTransactionCount; }
    public int       getSkippedTransactionCount() { return skippedTransactionCount; }
    public BigDecimal getOpeningBalance()         { return openingBalance; }
    public BigDecimal getClosingBalance()         { return closingBalance; }
    public BigDecimal getTotalDebits()            { return totalDebits; }
    public BigDecimal getTotalCredits()           { return totalCredits; }
    public int       getMonthlyProfilesCreated()  { return monthlyProfilesCreated; }
}