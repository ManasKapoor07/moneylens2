package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents one uploaded bank statement PDF.
 *
 * CHANGED from original:
 *   - Removed: statementYear (int), statementMonth (int)
 *   - Added:   statementFromDate (LocalDate), statementToDate (LocalDate)
 *
 * Why: A single PDF can cover any duration. The old year+month fields
 * assumed single-month PDFs. fromDate/toDate store the exact period
 * extracted from the PDF header ("From : 01/01/2026  To : 30/06/2026").
 *
 * The unique constraint is now on (user_id, bank_name, from_date, to_date)
 * so re-uploading the exact same period is blocked but uploading a
 * different date range that happens to overlap is allowed.
 * Duplicate transaction detection is handled at the Transaction level
 * via reference number dedup — that's the real guard against duplicates.
 */
@Entity
@Table(name = "bank_statements", indexes = {
        @Index(
                name  = "idx_stmt_user_period",
                columnList = "user_id, bank_name, statement_from_date, statement_to_date",
                unique = true
        )
})
public class BankStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "bank_name", length = 20, nullable = false)
    private String bankName;

    // ── CHANGED: date range replaces year+month ───────────────────────────────
    @Column(name = "statement_from_date", nullable = false)
    private LocalDate statementFromDate;

    @Column(name = "statement_to_date", nullable = false)
    private LocalDate statementToDate;

    // ── Status & counts ───────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementStatus status;

    @Column(name = "raw_transaction_count")
    private Integer rawTransactionCount;

    @Column(name = "parsed_transaction_count")
    private Integer parsedTransactionCount;

    // ── Balances from PDF summary ─────────────────────────────────────────────
    @Column(name = "opening_balance", precision = 15, scale = 2)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 15, scale = 2)
    private BigDecimal closingBalance;

    @Column(name = "total_debits", precision = 15, scale = 2)
    private BigDecimal totalDebits;

    @Column(name = "total_credits", precision = 15, scale = 2)
    private BigDecimal totalCredits;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    // ── Convenience helpers ───────────────────────────────────────────────────

    /**
     * How many calendar months does this statement span?
     * e.g. Jan–Jun = 6, single month = 1
     */
    public long getMonthsSpanned() {
        if (statementFromDate == null || statementToDate == null) return 1;
        return java.time.temporal.ChronoUnit.MONTHS.between(
                statementFromDate.withDayOfMonth(1),
                statementToDate.withDayOfMonth(1)) + 1;
    }

    public boolean isMultiMonth() {
        return getMonthsSpanned() > 1;
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    public enum StatementStatus {
        PROCESSING, PARSED, PROCESSED, FAILED
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId()                              { return id; }

    public User getUser()                            { return user; }
    public void setUser(User user)                   { this.user = user; }

    public String getBankName()                      { return bankName; }
    public void setBankName(String v)                { this.bankName = v; }

    public LocalDate getStatementFromDate()          { return statementFromDate; }
    public void setStatementFromDate(LocalDate v)    { this.statementFromDate = v; }

    public LocalDate getStatementToDate()            { return statementToDate; }
    public void setStatementToDate(LocalDate v)      { this.statementToDate = v; }

    public StatementStatus getStatus()               { return status; }
    public void setStatus(StatementStatus v)         { this.status = v; }

    public Integer getRawTransactionCount()          { return rawTransactionCount; }
    public void setRawTransactionCount(Integer v)    { this.rawTransactionCount = v; }

    public Integer getParsedTransactionCount()       { return parsedTransactionCount; }
    public void setParsedTransactionCount(Integer v) { this.parsedTransactionCount = v; }

    public BigDecimal getOpeningBalance()            { return openingBalance; }
    public void setOpeningBalance(BigDecimal v)      { this.openingBalance = v; }

    public BigDecimal getClosingBalance()            { return closingBalance; }
    public void setClosingBalance(BigDecimal v)      { this.closingBalance = v; }

    public BigDecimal getTotalDebits()               { return totalDebits; }
    public void setTotalDebits(BigDecimal v)         { this.totalDebits = v; }

    public BigDecimal getTotalCredits()              { return totalCredits; }
    public void setTotalCredits(BigDecimal v)        { this.totalCredits = v; }

    public String getErrorMessage()                  { return errorMessage; }
    public void setErrorMessage(String v)            { this.errorMessage = v; }

    public LocalDateTime getUploadedAt()             { return uploadedAt; }

    public LocalDateTime getParsedAt()               { return parsedAt; }
    public void setParsedAt(LocalDateTime v)         { this.parsedAt = v; }
}