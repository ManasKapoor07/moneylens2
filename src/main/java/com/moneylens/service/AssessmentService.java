package com.moneylens.service;

import com.moneylens.dto.AssessmentDto;
import com.moneylens.entity.User;
import com.moneylens.entity.UserAssessment;
import com.moneylens.repository.UserAssessmentRepository;
import com.moneylens.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Handles saving and retrieving the user onboarding assessment.
 *
 * The assessment is the user's self-declared financial snapshot collected
 * during onboarding. It feeds directly into FinancialProfileService for
 * sub-score computation and is cross-checked against statement data by
 * DeclaredVsActualAnalyzer (Tier-1 personalisation).
 *
 * Key fields stored:
 *   - Identity: fullName, occupation, occupationDetail
 *   - Income: monthlyIncome, monthlySavings, incomeSource, payFrequency
 *   - Goals: financialGoal, goalAmount, goalDeadline, retirementAge
 *   - Debt: hasDebt
 *   - Emergency: hasEmergencyFund, emergencyMonths
 *   - Behaviour: spendingBehaviour, spendingCategories, financeSentiment
 *   - Context: appPurpose, dependents, expenseTracking, financialChallenges
 *
 * Design notes:
 *   - Upsert semantics: if an assessment already exists for the user, it is
 *     fully overwritten. This allows users to re-take the assessment.
 *   - goalDeadline is accepted as a String (ISO-8601 or "YYYY-MM" format)
 *     and parsed to LocalDate before persistence.
 *   - monthlyIncome and monthlySavings from the DTO are stored as BigDecimal
 *     on the entity for precision-safe arithmetic in scoring.
 */
@Service
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    private final UserAssessmentRepository assessmentRepository;
    private final UserRepository           userRepository;
    private final ObjectMapper             objectMapper;

    public AssessmentService(UserAssessmentRepository assessmentRepository,
                             UserRepository userRepository,
                             ObjectMapper objectMapper) {
        this.assessmentRepository = assessmentRepository;
        this.userRepository       = userRepository;
        this.objectMapper         = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves (or updates) the onboarding assessment for the given user.
     * Idempotent — calling this multiple times with updated data replaces
     * the previous assessment.
     *
     * @param phoneNumber  The user's phone number (used as the account identifier)
     * @param request      The assessment payload from the frontend
     * @return             AssessmentResponse with the persisted assessment ID
     */
    @Transactional
    public AssessmentDto.AssessmentResponse saveAssessment(String phoneNumber,
                                                           AssessmentDto.SaveAssessmentRequest request) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found: " + phoneNumber));

        // Upsert: fetch existing or create new
        UserAssessment assessment = assessmentRepository.findByUserId(user.getId())
                .orElse(new UserAssessment());

        boolean isNew = assessment.getId() == null;

        // ── Identity & occupation ──────────────────────────────────────────
        assessment.setUser(user);
        assessment.setFullName(request.getFullName());
        assessment.setOccupation(request.getOccupation());
        assessment.setOccupationDetail(request.getOccupationDetail());
        assessment.setIncomeSource(request.getIncomeSource());

        // ── Income & savings — convert Double to BigDecimal ────────────────
        assessment.setMonthlyIncome(
                request.getMonthlyIncome() != null
                        ? BigDecimal.valueOf(request.getMonthlyIncome()) : null);
        assessment.setMonthlySavings(
                request.getMonthlySavings() != null
                        ? BigDecimal.valueOf(request.getMonthlySavings()) : null);
        assessment.setPayFrequency(request.getPayFrequency());

        // ── Spending behaviour ─────────────────────────────────────────────
        assessment.setSpendingCategories(request.getSpendingCategories());
        assessment.setSpendingBehaviour(request.getSpendingBehaviour());
        assessment.setExpenseTracking(request.getExpenseTracking());

        // ── Debt ───────────────────────────────────────────────────────────
        assessment.setHasDebt(request.getHasDebt());

        // ── Financial goal ─────────────────────────────────────────────────
        assessment.setFinancialGoal(request.getFinancialGoal());
        assessment.setGoalAmount(
                request.getGoalAmount() != null
                        ? BigDecimal.valueOf(request.getGoalAmount()) : null);
        assessment.setGoalDeadline(parseGoalDeadline(request.getGoalDeadline()));

        // ── Personal context ───────────────────────────────────────────────
        assessment.setRetirementAge(request.getRetirementAge());
        assessment.setDependents(request.getDependents());
        assessment.setFinanceSentiment(request.getFinanceSentiment());

        // ── Emergency fund ─────────────────────────────────────────────────
        assessment.setHasEmergencyFund(request.getHasEmergencyFund());
        assessment.setEmergencyMonths(request.getEmergencyMonths());

        // ── App usage context ──────────────────────────────────────────────
        assessment.setAppPurpose(request.getAppPurpose());
        assessment.setFinancialChallenges(request.getFinancialChallenges());

        // ── Committed expenses ─────────────────────────────────────────────
        if (request.getCommittedExpenses() != null) {
            try {
                assessment.setCommittedExpensesJson(
                        objectMapper.writeValueAsString(request.getCommittedExpenses()));
            } catch (Exception ex) {
                log.warn("Could not serialize committedExpenses: {}", ex.getMessage());
            }
        }

        // ── Persist ────────────────────────────────────────────────────────
        UserAssessment saved = assessmentRepository.save(assessment);

        log.info("Assessment {} for user={} (id={}) — income=₹{} savings=₹{} goal={}",
                isNew ? "created" : "updated",
                phoneNumber,
                user.getId(),
                saved.getMonthlyIncome() != null ? saved.getMonthlyIncome().toPlainString() : "N/A",
                saved.getMonthlySavings() != null ? saved.getMonthlySavings().toPlainString() : "N/A",
                saved.getFinancialGoal() != null ? saved.getFinancialGoal() : "not set");

        return new AssessmentDto.AssessmentResponse(
                saved.getId(),
                isNew ? "Assessment saved successfully" : "Assessment updated successfully");
    }

    /**
     * Returns true if the given user has completed the onboarding assessment.
     * Used by the controller to gate profile-dependent endpoints.
     */
    public boolean hasCompletedAssessment(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found: " + phoneNumber));
        return assessmentRepository.existsByUserId(user.getId());
    }

    /**
     * Retrieves the assessment DTO for the given user.
     * Useful for pre-filling the assessment form on re-visit.
     */
    public UserAssessment getAssessment(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found: " + phoneNumber));
        return assessmentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Assessment not found for user: " + phoneNumber));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses the goal deadline string from the frontend.
     *
     * Supported formats:
     *   "2026-06-01"  → parsed as LocalDate directly (ISO-8601 full date)
     *   "2026-06"     → interpreted as the first day of that month
     *   "2026"        → interpreted as Jan 1 of that year
     *   null / blank  → returns null (no deadline set)
     *
     * Any unparseable format logs a warning and returns null.
     */
    private LocalDate parseGoalDeadline(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String trimmed = raw.trim();

        // Full ISO date: YYYY-MM-DD
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) { }

        // Year-month: YYYY-MM → first of month
        try {
            return LocalDate.parse(trimmed + "-01", DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) { }

        // Year only: YYYY → Jan 1
        try {
            int year = Integer.parseInt(trimmed);
            return LocalDate.of(year, 1, 1);
        } catch (NumberFormatException ignored) { }

        log.warn("Could not parse goalDeadline '{}' — storing null", raw);
        return null;
    }
}