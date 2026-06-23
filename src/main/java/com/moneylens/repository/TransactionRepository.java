package com.moneylens.repository;

import com.moneylens.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByUserIdOrderByDateDesc(Long userId);


    List<Transaction> findByUserIdAndStatementYearAndStatementMonth(
            Long userId, int year, int month);


    /** Used for duplicate detection - check if any of these refs already exist for this user */
    @Query("SELECT t.referenceNumber FROM Transaction t WHERE t.user.id = :userId AND t.referenceNumber IN :refs")
    Set<String> findExistingReferenceNumbers(@Param("userId") Long userId,
                                             @Param("refs") List<String> refs);

    boolean existsByUserIdAndStatementYearAndStatementMonthAndBankName(
            Long userId, int year, int month, String bankName);

    long countByUserId(Long userId);
}