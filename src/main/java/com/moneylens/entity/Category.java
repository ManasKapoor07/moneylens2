package com.moneylens.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Category {
    FOOD_AND_DINING("Food & Dining"),
    GROCERIES("Groceries"),
    TRANSPORT("Transport"),
    SHOPPING("Shopping"),
    ENTERTAINMENT("Entertainment"),
    UTILITIES_AND_BILLS("Utilities & Bills"),
    RENT_AND_HOUSING("Rent & Housing"),
    HEALTH_AND_FITNESS("Health & Fitness"),
    TRAVEL("Travel"),
    TRANSFERS("Transfers"),
    LOAN_AND_EMI("Loan & EMI"),
    INVESTMENT("Investment"),
    REFUND("Refund"),
    CASH_WITHDRAWAL("Cash Withdrawal"),
    OTHER("Other");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    /** Accepts both display name ("Food & Dining") and enum name ("FOOD_AND_DINING"). */
    @JsonCreator
    public static Category fromString(String value) {
        if (value == null) return null;
        for (Category c : values()) {
            if (c.displayName.equalsIgnoreCase(value) || c.name().equalsIgnoreCase(value)) {
                return c;
            }
        }
        return OTHER;
    }
}