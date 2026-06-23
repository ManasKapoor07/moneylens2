package com.moneylens.repository;

import com.moneylens.entity.BankStatement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * CHANGED from original:
 *   - Removed: existsByUserIdAndBankNameAndStatementYearAndStatementMonth()
 *   - Added:   existsByUserIdAndBankNameAndStatementFromDateAndStatementToDate()
 *   - Added:   existsByUserIdAndBankNameAndOverlappingPeriod() for overlap detection
 *   - Changed: findByUserIdOrderBy... now orders by statementFromDate DESC
 */
@Repository
public interface BankStatementRepository extends JpaRepository<BankStatement, Long> {

    /**
     * Exact duplicate check — same bank, same from+to dates.
     * Used to block re-uploading the identical PDF.
     */
    boolean existsByUserIdAndBankNameAndStatementFromDateAndStatementToDate(
            Long userId,
            String bankName,
            LocalDate statementFromDate,
            LocalDate statementToDate
    );

    /**
     * Overlap check — does any existing statement for this user+bank
     * overlap with the period being uploaded?
     *
     * Two periods overlap when:
     *   existing.from <= new.to  AND  existing.to >= new.from
     *
     * Used to warn (not block) when uploading a period that partially
     * overlaps an existing statement. Transaction-level dedup handles
     * the actual duplicate prevention.
     */
    @Query("""
            SELECT COUNT(s) > 0
            FROM BankStatement s
            WHERE s.user.id = :userId
              AND s.bankName = :bankName
              AND s.statementFromDate <= :toDate
              AND s.statementToDate   >= :fromDate
            """)
    boolean existsOverlappingPeriod(
            @Param("userId")   Long userId,
            @Param("bankName") String bankName,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate
    );

    /**
     * All statements for a user, newest first.
     */
    List<BankStatement> findByUserIdOrderByStatementFromDateDesc(Long userId);

    /**
     * All statements for a user and bank, newest first.
     */
    List<BankStatement> findByUserIdAndBankNameOrderByStatementFromDateDesc(
            Long userId, String bankName
    );
}