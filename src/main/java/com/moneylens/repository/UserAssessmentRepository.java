package com.moneylens.repository;

import com.moneylens.entity.UserAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserAssessmentRepository extends JpaRepository<UserAssessment, Long> {

    Optional<UserAssessment> findByUserId(Long userId);

    boolean existsByUserId(Long userId);
}