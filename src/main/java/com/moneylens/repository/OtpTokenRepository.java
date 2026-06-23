package com.moneylens.repository;

import com.moneylens.entity.OtpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {

    Optional<OtpToken> findTopByPhoneNumberAndUsedFalseOrderByCreatedAtDesc(String phoneNumber);

    @Modifying
    @Query("UPDATE OtpToken o SET o.used = true WHERE o.phoneNumber = :phoneNumber AND o.used = false")
    void invalidateAllByPhoneNumber(String phoneNumber);
}
