package com.moneylens.dto;

import com.moneylens.entity.TransactionMode;
import com.moneylens.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Internal DTO produced by bank parsers. Not exposed to API consumers.
 *
 * statementYear and statementMonth are set from the transaction's OWN date
 * by the parser — not from the statement period header.
 * A March transaction in a Jan–Jun PDF gets statementMonth=3.
 */
public class ParsedTransaction {

    private LocalDate  date;
    private LocalDate  valueDate;
    private String     rawNarration;
    private String     referenceNumber;
    private BigDecimal withdrawalAmount;
    private BigDecimal depositAmount;
    private BigDecimal closingBalance;
    private String     merchantName;
    private TransactionType type;
    private TransactionMode mode;
    private String     upiHandle;
    private String     counterpartyName;
    private String     counterpartyPhone;
    private boolean    isRefund;

    // Set by parser from the transaction's own date
    private int statementYear;
    private int statementMonth;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public LocalDate getDate()                    { return date; }
    public void setDate(LocalDate v)              { this.date = v; }

    public LocalDate getValueDate()               { return valueDate; }
    public void setValueDate(LocalDate v)         { this.valueDate = v; }

    public String getRawNarration()               { return rawNarration; }
    public void setRawNarration(String v)         { this.rawNarration = v; }

    public String getReferenceNumber()            { return referenceNumber; }
    public void setReferenceNumber(String v)      { this.referenceNumber = v; }

    public BigDecimal getWithdrawalAmount()       { return withdrawalAmount; }
    public void setWithdrawalAmount(BigDecimal v) { this.withdrawalAmount = v; }

    public BigDecimal getDepositAmount()          { return depositAmount; }
    public void setDepositAmount(BigDecimal v)    { this.depositAmount = v; }

    public BigDecimal getClosingBalance()         { return closingBalance; }
    public void setClosingBalance(BigDecimal v)   { this.closingBalance = v; }

    public String getMerchantName()               { return merchantName; }
    public void setMerchantName(String v)         { this.merchantName = v; }

    public TransactionType getType()              { return type; }
    public void setType(TransactionType v)        { this.type = v; }

    public TransactionMode getMode()              { return mode; }
    public void setMode(TransactionMode v)        { this.mode = v; }

    public String getUpiHandle()                  { return upiHandle; }
    public void setUpiHandle(String v)            { this.upiHandle = v; }

    public String getCounterpartyName()           { return counterpartyName; }
    public void setCounterpartyName(String v)     { this.counterpartyName = v; }

    public String getCounterpartyPhone()          { return counterpartyPhone; }
    public void setCounterpartyPhone(String v)    { this.counterpartyPhone = v; }

    public boolean isRefund()                     { return isRefund; }
    public void setRefund(boolean v)              { this.isRefund = v; }

    public int getStatementYear()                 { return statementYear; }
    public void setStatementYear(int v)           { this.statementYear = v; }

    public int getStatementMonth()                { return statementMonth; }
    public void setStatementMonth(int v)          { this.statementMonth = v; }
}