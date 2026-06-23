package com.moneylens.repository;

import com.moneylens.entity.StatementProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StatementProfileRepository extends JpaRepository<StatementProfile, Long> {

    /**
     * Find a specific month's profile for a user.
     * Used by ProfileController GET /profile/month/{year}/{month}
     */
    Optional<StatementProfile> findByUserIdAndProfileYearAndProfileMonth(
            Long userId, int profileYear, int profileMonth);

    /**
     * Check if a monthly profile already exists before creating one.
     * Prevents duplicate profiles when overlapping statements are uploaded.
     */
    boolean existsByUserIdAndProfileYearAndProfileMonth(
            Long userId, int profileYear, int profileMonth);

    /**
     * All monthly profiles for a user, newest first.
     * Used by OverallProfileService to aggregate across months.
     */
    List<StatementProfile> findByUserIdOrderByProfileYearDescProfileMonthDesc(Long userId);

    /**
     * All monthly profiles for a specific uploaded statement.
     * Used to fetch all months produced by one PDF upload.
     */
    List<StatementProfile> findByStatementIdOrderByProfileYearAscProfileMonthAsc(Long statementId);

    /**
     * Last N months of profiles for a user — used for trend computation.
     * e.g. last 3 months to determine IMPROVING / DECLINING / STABLE
     */
    @Query("""
            SELECT p FROM StatementProfile p
            WHERE p.user.id = :userId
            ORDER BY p.profileYear DESC, p.profileMonth DESC
            LIMIT :limit
            """)
    List<StatementProfile> findLatestProfiles(
            @Param("userId") Long userId,
            @Param("limit")  int limit);

    /**
     * Count of monthly profiles for a user.
     * Used by OverallProfileService for monthsAnalyzed field.
     */
    long countByUserId(Long userId);
    Optional<StatementProfile> findTopByUserIdOrderByProfileYearDescProfileMonthDesc(
            Long userId);

    /**
     * Delete all profiles linked to a specific statement.
     * Used if a statement needs to be re-processed.
     */
    void deleteByStatementId(Long statementId);
}