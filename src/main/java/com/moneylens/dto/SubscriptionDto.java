package com.moneylens.dto;

import java.math.BigDecimal;
import java.util.List;

public class SubscriptionDto {

    public record SubscriptionItem(
            String  merchant,
            String  merchantKey,
            String  type,          // SUBSCRIPTION, EMI, RENT, UTILITY, TRANSFER
            String  confidence,    // POSSIBLE, LIKELY, CONFIRMED
            BigDecimal amount,
            int     monthsDetected,
            String  lastSeen       // "YYYY-MM"
    ) {}

    public record SubscriptionsResponse(
            List<SubscriptionItem> subscriptions,
            BigDecimal totalMonthlyBurden,
            int confirmedCount
    ) {}
}
