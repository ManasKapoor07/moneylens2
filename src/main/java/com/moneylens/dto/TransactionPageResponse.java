package com.moneylens.dto;

import java.math.BigDecimal;
import java.util.List;

public record TransactionPageResponse(
    List<TransactionDto> transactions,
    long       totalCount,
    int        page,
    int        size,
    int        totalPages,
    boolean    hasMore,
    /** Sum of all deposit amounts in the full filtered set (not just this page). */
    BigDecimal filteredCredits,
    /** Sum of all withdrawal amounts in the full filtered set (not just this page). */
    BigDecimal filteredDebits
) {}
