package com.moneylens.repository;

import com.moneylens.entity.RecurringPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecurringPaymentRepository extends JpaRepository<RecurringPayment, Long> {

    /**
     * All recurring payments for a user across all months, newest first.
     * Used by OverallProfileService to aggregate and compute confidence.
     */
    List<RecurringPayment> findByUserIdOrderByProfileYearDescProfileMonthDesc(Long userId);

    /**
     * All recurring payments for a specific month's profile.
     * Used by ProfileController GET /profile/month/{year}/{month}
     */
    List<RecurringPayment> findByStatementProfileIdOrderByAmountDesc(Long statementProfileId);

    /**
     * All recurring payments for a specific user + month.
     * Shortcut used when we already know year/month but not profile id.
     */
    List<RecurringPayment> findByUserIdAndProfileYearAndProfileMonth(
            Long userId, int profileYear, int profileMonth);

    /**
     * How many distinct months has this merchant been detected for a user?
     * Used by OverallProfileService to compute Confidence level.
     *
     * e.g. Netflix detected in Jan, Feb, Mar, Apr, May, Jun → returns 6
     */
    @Query("""
            SELECT COUNT(DISTINCT CONCAT(r.profileYear, '-', r.profileMonth))
            FROM RecurringPayment r
            WHERE r.user.id    = :userId
              AND r.merchantKey = :merchantKey
            """)
    long countDistinctMonthsByMerchantKey(
            @Param("userId")      Long userId,
            @Param("merchantKey") String merchantKey
    );

    /**
     * All unique merchant keys seen across all months for a user.
     * Used by OverallProfileService to find confirmed recurring obligations.
     */
    @Query("""
            SELECT DISTINCT r.merchantKey
            FROM RecurringPayment r
            WHERE r.user.id = :userId
            """)
    List<String> findDistinctMerchantKeysByUserId(@Param("userId") Long userId);

    /**
     * Confirmed recurring payments — merchants detected in 4+ months.
     * Used by AiCopilotService to surface "you've been paying X for N months".
     */
    @Query("""
            SELECT r FROM RecurringPayment r
            WHERE r.user.id = :userId
              AND r.confidence = com.moneylens.entity.RecurringPayment$Confidence.CONFIRMED
            ORDER BY r.amount DESC
            """)
    List<RecurringPayment> findConfirmedByUserId(@Param("userId") Long userId);

    /**
     * Undeclared recurring payments for a user — items the user never
     * mentioned in their assessment. Surface as insights in the copilot.
     */
    @Query("""
            SELECT r FROM RecurringPayment r
            WHERE r.user.id              = :userId
              AND r.declaredInAssessment  = false
              AND r.confidence IN (
                  com.moneylens.entity.RecurringPayment$Confidence.LIKELY,
                  com.moneylens.entity.RecurringPayment$Confidence.CONFIRMED
              )
            ORDER BY r.amount DESC
            """)
    List<RecurringPayment> findUndeclaredByUserId(@Param("userId") Long userId);
    List<RecurringPayment> findByUserIdOrderByAmountDesc(Long userId);

    List<RecurringPayment> findByUserIdAndDeclaredInAssessmentFalseAndConfidenceIn(
            Long userId, List<RecurringPayment.Confidence> confidences);


    /**
     * Delete all recurring payments for a specific statement profile.
     * Used when a profile is rebuilt after re-upload.
     */
    void deleteByStatementProfileId(Long statementProfileId);

    /**
     * Total monthly recurring burden for a user — sum of all confirmed
     * recurring payments. Used in OverallProfile for fixed cost calculation.
     */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0)
            FROM RecurringPayment r
            WHERE r.user.id    = :userId
              AND r.confidence  = com.moneylens.entity.RecurringPayment$Confidence.CONFIRMED
              AND r.profileYear  = :year
              AND r.profileMonth = :month
            """)
    java.math.BigDecimal sumConfirmedAmountForMonth(
            @Param("userId") Long userId,
            @Param("year")   int year,
            @Param("month")  int month
    );
}