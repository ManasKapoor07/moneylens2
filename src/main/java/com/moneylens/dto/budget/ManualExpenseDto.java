package com.moneylens.dto.budget;

import java.math.BigDecimal;

public class ManualExpenseDto {
    public Long id;
    public String category;
    public BigDecimal amount;
    public String note;
    public String spentAt;
}