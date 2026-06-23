package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores a single detected recurring / fixed expense for one calendar month.
 *
 * One row per detected item per month. If Netflix appears in 6 months,
 * there are 6 rows — one per StatementProfile. This lets OverallProfileService
 * query "which merchants appear in 3+ consecutive months" and mark them
 * as CONFIRMED_RECURRING.
 *
 * Created by StatementProfileService during upload, after RecurringExpenseDetector
 * runs on that month's transactions.
 *
 * Recurring confidence levels (set by OverallProfileService after aggregation):
 *   POSSIBLE   — detected in 1 month
 *   LIKELY     — detected in 2–3 months
 *   CONFIRMED  — detected in 4+ months
 */
@Entity
@Table(name = "recurring_payments", indexes = {
        @Index(name = "idx_recurring_user",    columnList = "user_id"),
        @Index(name = "idx_recurring_profile", columnList = "statement_profile_id"),
        @Index(name = "idx_recurring_merchant",columnList = "user_id, merchant_key")
})
public class RecurringPayment {

    public enum RecurringType {
        SUBSCRIPTION,    // Netflix, Spotify, Airtel recharge etc.
        EMI,             // loan EMI / AUTOPAY tagged by parser
        RENT,            // large person transfer >= 15% of income
        REPEATED_DEBIT   // same merchant 2+ times, similar amounts
    }

    public enum Confidence {
        POSSIBLE,    // seen in 1 month
        LIKELY,      // seen in 2–3 months
        CONFIRMED    // seen in 4+ months
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Relationships ─────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_profile_id", nullable = false)
    private StatementProfile statementProfile;

    // ── Which month this detection belongs to ─────────────────────────────────

    @Column(name = "profile_year",  nullable = false)
    private int profileYear;

    @Column(name = "profile_month", nullable = false)
    private int profileMonth;

    // ── Merchant info ─────────────────────────────────────────────────────────

    /**
     * Canonical merchant name — e.g. "Netflix", "KreditBee", "House Rent".
     * Displayed to the user.
     */
    @Column(name = "merchant", length = 100, nullable = false)
    private String merchant;

    /**
     * Normalised lowercase key used for cross-month matching.
     * e.g. "netflix", "kreditbee", "house rent"
     * Same merchant detected in different months must produce the same key.
     */
    @Column(name = "merchant_key", length = 100, nullable = false)
    private String merchantKey;

    // ── Amount ────────────────────────────────────────────────────────────────

    @Column(name = "amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * How many times this merchant appeared this month.
     * Usually 1, but could be 2 for fortnightly charges.
     */
    @Column(name = "occurrences")
    private int occurrences = 1;

    // ── Classification ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "recurring_type", length = 20, nullable = false)
    private RecurringType recurringType;

    /**
     * Was this obligation declared in the user's assessment?
     * false = user forgot about it or didn't mention it — surfaces as an insight.
     */
    @Column(name = "declared_in_assessment")
    private boolean declaredInAssessment;

    /**
     * Confidence level — set by OverallProfileService after aggregating
     * across all months. Default POSSIBLE on creation, upgraded later.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", length = 20)
    private Confidence confidence = Confidence.POSSIBLE;

    /**
     * How many total months this merchant has been detected across
     * all uploaded statements. Updated by OverallProfileService.
     */
    @Column(name = "months_detected")
    private int monthsDetected = 1;

    // ── Detection metadata ────────────────────────────────────────────────────

    @Column(name = "detection_reason", columnDefinition = "TEXT")
    private String detectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId()                              { return id; }

    public User getUser()                            { return user; }
    public void setUser(User v)                      { this.user = v; }

    public StatementProfile getStatementProfile()    { return statementProfile; }
    public void setStatementProfile(StatementProfile v) { this.statementProfile = v; }

    public int getProfileYear()                      { return profileYear; }
    public void setProfileYear(int v)                { this.profileYear = v; }

    public int getProfileMonth()                     { return profileMonth; }
    public void setProfileMonth(int v)               { this.profileMonth = v; }

    public String getMerchant()                      { return merchant; }
    public void setMerchant(String v)                { this.merchant = v; }

    public String getMerchantKey()                   { return merchantKey; }
    public void setMerchantKey(String v)             { this.merchantKey = v; }

    public BigDecimal getAmount()                    { return amount; }
    public void setAmount(BigDecimal v)              { this.amount = v; }

    public int getOccurrences()                      { return occurrences; }
    public void setOccurrences(int v)                { this.occurrences = v; }

    public RecurringType getRecurringType()          { return recurringType; }
    public void setRecurringType(RecurringType v)    { this.recurringType = v; }

    public boolean isDeclaredInAssessment()          { return declaredInAssessment; }
    public void setDeclaredInAssessment(boolean v)   { this.declaredInAssessment = v; }

    public Confidence getConfidence()                { return confidence; }
    public void setConfidence(Confidence v)          { this.confidence = v; }

    public int getMonthsDetected()                   { return monthsDetected; }
    public void setMonthsDetected(int v)             { this.monthsDetected = v; }

    public String getDetectionReason()               { return detectionReason; }
    public void setDetectionReason(String v)         { this.detectionReason = v; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
}