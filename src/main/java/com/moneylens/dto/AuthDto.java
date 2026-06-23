package com.moneylens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class AuthDto {

    public record SendOtpRequest(
            @NotBlank(message = "Phone number is required")
            @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone number must be in E.164 format e.g. +919876543210")
            String phoneNumber
    ) {}

    public record VerifyOtpRequest(
            @NotBlank(message = "Phone number is required")
            @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone number must be in E.164 format")
            String phoneNumber,

            @NotBlank(message = "OTP is required")
            @Pattern(regexp = "^\\d{6}$", message = "OTP must be 6 digits")
            String otp
    ) {}

    public record SendOtpResponse(
            String message,
            String phoneNumber
    ) {}

    public record AuthResponse(
            String token,
            String phoneNumber,
            boolean isNewUser,
            long expiresIn
    ) {}

    public record ErrorResponse(
            String error,
            String message,
            int status
    ) {}

    // ── /me response ─────────────────────────────────────────────────────────
    // Returns user fields + assessment fields in one call.
    // Assessment fields are null if the user hasn't completed onboarding yet.

    public record MeResponse(
            // User fields
            Long userId,
            String phoneNumber,
            boolean verified,

            // Assessment fields (null if not yet completed)
            boolean assessmentCompleted,
            String fullName,
            List<String> appPurpose,
            String occupation,
            String occupationDetail,
            String incomeSource,
            BigDecimal monthlyIncome,
            BigDecimal monthlySavings,
            String payFrequency,
            List<String> spendingCategories,
            Boolean hasDebt,
            String financialGoal,
            BigDecimal goalAmount,
            LocalDate goalDeadline,
            String expenseTracking,
            Integer retirementAge,
            Integer dependents,
            String financeSentiment,
            Boolean hasEmergencyFund,
            Integer emergencyMonths,
            String spendingBehaviour,
            List<String> financialChallenges
    ) {}
}