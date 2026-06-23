package com.moneylens.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_transaction_user",  columnList = "user_id"),
        @Index(name = "idx_transaction_ref",   columnList = "reference_number, user_id", unique = true),
        @Index(name = "idx_transaction_month", columnList = "user_id, statement_year, statement_month")
})
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // ── Raw parsed fields ─────────────────────────────────────────────────────

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "category")
    private String category;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "raw_narration", columnDefinition = "TEXT", nullable = false)
    private String rawNarration;

    @Column(name = "reference_number", length = 20)
    private String referenceNumber;

    @Column(name = "withdrawal_amount", precision = 15, scale = 2)
    private BigDecimal withdrawalAmount;

    @Column(name = "deposit_amount", precision = 15, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "closing_balance", precision = 15, scale = 2)
    private BigDecimal closingBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    // ── Derived at parse time ─────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TransactionMode mode;

    @Column(name = "upi_handle", length = 100)
    private String upiHandle;

    @Column(name = "counterparty_name", length = 200)
    private String counterpartyName;

    @Column(name = "counterparty_phone", length = 15)
    private String counterpartyPhone;

    @Column(name = "is_refund")
    private boolean isRefund;

    // ── Categorization ────────────────────────────────────────────────────────

    @Column(name = "merchant_name", length = 200)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_category", length = 30)
    private Category systemCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_category", length = 30)
    private Category userCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_source", length = 10)
    private CategorySource categorySource;

    @Column(name = "is_recurring")
    private Boolean isRecurring;

    // ── Statement metadata ────────────────────────────────────────────────────

    @Column(name = "bank_name", length = 20, nullable = false)
    private String bankName;

    @Column(name = "statement_year", nullable = false)
    private int statementYear;

    @Column(name = "statement_month", nullable = false)
    private int statementMonth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Computed helpers ──────────────────────────────────────────────────────

    @Transient
    public Category getEffectiveCategory() {
        return userCategory != null ? userCategory : systemCategory;
    }

    public BigDecimal getAmount() {
        return type == TransactionType.DEBIT ? withdrawalAmount : depositAmount;
    }

    public YearMonth getStatementYearMonth() {
        return YearMonth.of(statementYear, statementMonth);
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId() { return id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }

    public String getRawNarration() { return rawNarration; }
    public void setRawNarration(String rawNarration) { this.rawNarration = rawNarration; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public BigDecimal getWithdrawalAmount() { return withdrawalAmount; }
    public void setWithdrawalAmount(BigDecimal withdrawalAmount) { this.withdrawalAmount = withdrawalAmount; }

    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }

    public BigDecimal getClosingBalance() { return closingBalance; }
    public void setClosingBalance(BigDecimal closingBalance) { this.closingBalance = closingBalance; }

    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }

    public TransactionMode getMode() { return mode; }
    public void setMode(TransactionMode mode) { this.mode = mode; }

    public String getUpiHandle() { return upiHandle; }
    public void setUpiHandle(String upiHandle) { this.upiHandle = upiHandle; }

    public String getCounterpartyName() { return counterpartyName; }
    public void setCounterpartyName(String counterpartyName) { this.counterpartyName = counterpartyName; }

    public String getCounterpartyPhone() { return counterpartyPhone; }
    public void setCounterpartyPhone(String counterpartyPhone) { this.counterpartyPhone = counterpartyPhone; }

    public boolean isRefund() { return isRefund; }
    public void setRefund(boolean refund) { isRefund = refund; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public String getCategory()           { return category; }
    public void setCategory(String v)     { this.category = v; }

    public Category getSystemCategory() { return systemCategory; }
    public void setSystemCategory(Category systemCategory) { this.systemCategory = systemCategory; }

    public Category getUserCategory() { return userCategory; }
    public void setUserCategory(Category userCategory) { this.userCategory = userCategory; }

    public CategorySource getCategorySource() { return categorySource; }
    public void setCategorySource(CategorySource categorySource) { this.categorySource = categorySource; }

    public Boolean getIsRecurring() { return isRecurring; }
    public void setIsRecurring(Boolean isRecurring) { this.isRecurring = isRecurring; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public int getStatementYear() { return statementYear; }
    public void setStatementYear(int statementYear) { this.statementYear = statementYear; }

    public int getStatementMonth() { return statementMonth; }
    public void setStatementMonth(int statementMonth) { this.statementMonth = statementMonth; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}