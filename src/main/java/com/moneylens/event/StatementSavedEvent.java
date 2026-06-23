package com.moneylens.event;

import com.moneylens.entity.Transaction;

import java.util.List;

public class StatementSavedEvent {

    private final Long statementId;
    private final Long userId;
    private final List<Long> transactionIds;

    public StatementSavedEvent(Long statementId, Long userId, List<Long> transactionIds) {
        this.statementId    = statementId;
        this.userId         = userId;
        this.transactionIds = transactionIds;
    }

    public Long getStatementId()         { return statementId; }
    public Long getUserId()              { return userId; }
    public List<Long> getTransactionIds() { return transactionIds; }
}
