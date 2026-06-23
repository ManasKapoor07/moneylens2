package com.moneylens.dto;

import com.moneylens.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Public-facing DTO for a transaction.
 * Uses Category display names (via @JsonValue on the enum), hides the JPA User reference.
 */
public class TransactionDto {

    private Long        id;
    private LocalDate   date;
    private LocalDate   valueDate;
    private String      type;           // "CREDIT" | "DEBIT"
    private BigDecimal  withdrawalAmount;
    private BigDecimal  depositAmount;
    private BigDecimal  closingBalance;
    private String      rawNarration;
    private String      referenceNumber;
    private String      merchantName;
    private String      counterpartyName;
    private String      counterpartyPhone;
    private String      upiHandle;
    private String      mode;
    private String      category;           // legacy raw string
    private String      systemCategory;     // display name e.g. "Food & Dining"
    private String      userCategory;       // display name or null
    private String      effectiveCategory;  // userCategory ?? systemCategory
    private String      categorySource;
    private Boolean     isRecurring;
    private boolean     isRefund;
    private String      bankName;
    private int         statementYear;
    private int         statementMonth;
    /** Convenience: the transaction amount regardless of direction (withdrawalAmount for DEBIT, depositAmount for CREDIT). */
    private BigDecimal  amount;

    public static TransactionDto from(Transaction t) {
        TransactionDto d = new TransactionDto();
        d.id               = t.getId();
        d.date             = t.getDate();
        d.valueDate        = t.getValueDate();
        d.type             = t.getType() != null ? t.getType().name() : null;
        d.withdrawalAmount = t.getWithdrawalAmount();
        d.depositAmount    = t.getDepositAmount();
        d.closingBalance   = t.getClosingBalance();
        d.rawNarration     = t.getRawNarration();
        d.referenceNumber  = t.getReferenceNumber();
        d.merchantName     = t.getMerchantName();
        d.counterpartyName = t.getCounterpartyName();
        d.counterpartyPhone= t.getCounterpartyPhone();
        d.upiHandle        = t.getUpiHandle();
        d.mode             = t.getMode() != null ? t.getMode().name() : null;
        d.category         = t.getCategory();
        d.systemCategory   = t.getSystemCategory() != null ? t.getSystemCategory().getDisplayName() : null;
        d.userCategory     = t.getUserCategory()   != null ? t.getUserCategory().getDisplayName()   : null;
        d.effectiveCategory= t.getEffectiveCategory() != null ? t.getEffectiveCategory().getDisplayName() : null;
        d.categorySource   = t.getCategorySource() != null ? t.getCategorySource().name() : null;
        d.isRecurring      = t.getIsRecurring();
        d.isRefund         = t.isRefund();
        d.bankName         = t.getBankName();
        d.statementYear    = t.getStatementYear();
        d.statementMonth   = t.getStatementMonth();
        d.amount           = t.getAmount();
        return d;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long        getId()               { return id; }
    public LocalDate   getDate()             { return date; }
    public LocalDate   getValueDate()        { return valueDate; }
    public String      getType()             { return type; }
    public BigDecimal  getWithdrawalAmount() { return withdrawalAmount; }
    public BigDecimal  getDepositAmount()    { return depositAmount; }
    public BigDecimal  getClosingBalance()   { return closingBalance; }
    public String      getRawNarration()     { return rawNarration; }
    public String      getReferenceNumber()  { return referenceNumber; }
    public String      getMerchantName()     { return merchantName; }
    public String      getCounterpartyName() { return counterpartyName; }
    public String      getCounterpartyPhone(){ return counterpartyPhone; }
    public String      getUpiHandle()        { return upiHandle; }
    public String      getMode()             { return mode; }
    public String      getCategory()         { return category; }
    public String      getSystemCategory()   { return systemCategory; }
    public String      getUserCategory()     { return userCategory; }
    public String      getEffectiveCategory(){ return effectiveCategory; }
    public String      getCategorySource()   { return categorySource; }
    public Boolean     getIsRecurring()      { return isRecurring; }
    public boolean     isRefund()            { return isRefund; }
    public String      getBankName()         { return bankName; }
    public int         getStatementYear()    { return statementYear; }
    public int         getStatementMonth()   { return statementMonth; }
    public BigDecimal  getAmount()           { return amount; }
}
