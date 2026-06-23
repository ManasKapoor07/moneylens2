package com.moneylens.repository;

import com.moneylens.entity.OverallProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OverallProfileRepository extends JpaRepository<OverallProfile, Long> {

    /**
     * Fetch the overall profile for a user.
     * Used by ProfileController GET /api/v1/profile/overall
     * and by AiCopilotService to build copilot context.
     */
    Optional<OverallProfile> findByUserId(Long userId);

    /**
     * Check if an overall profile exists before deciding
     * whether to create or update in OverallProfileService.refresh().
     */
    boolean existsByUserId(Long userId);

    /**
     * Delete overall profile for a user.
     * Used if all statements are deleted and profile needs full reset.
     */
    void deleteByUserId(Long userId);
}