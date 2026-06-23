package com.moneylens.repository;

import com.moneylens.entity.FinancialGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Long> {

    List<FinancialGoal> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<FinancialGoal> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndStatus(Long userId, String status);
}
