package com.moneylens.repository;

import com.moneylens.entity.ManualExpense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ManualExpenseRepository extends JpaRepository<ManualExpense, Long> {
    List<ManualExpense> findByUserIdAndSpentAtBetween(Long userId, LocalDateTime from, LocalDateTime to);
    List<ManualExpense> findByUserIdOrderBySpentAtDesc(Long userId);
}