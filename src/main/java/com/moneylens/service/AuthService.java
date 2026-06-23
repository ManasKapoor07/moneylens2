package com.moneylens.service;

import com.moneylens.dto.AuthDto;
import com.moneylens.entity.OtpToken;
import com.moneylens.entity.User;
import com.moneylens.entity.UserAssessment;
import com.moneylens.exception.OtpException;
import com.moneylens.repository.OtpTokenRepository;
import com.moneylens.repository.UserAssessmentRepository;
import com.moneylens.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final OtpTokenRepository otpTokenRepository;
    private final UserAssessmentRepository assessmentRepository;
    private final TwilioService twilioService;
    private final JwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${otp.expiry-minutes:5}")
    private int otpExpiryMinutes;

    @Value("${otp.max-attempts:3}")
    private int maxAttempts;

    public AuthService(UserRepository userRepository,
                       OtpTokenRepository otpTokenRepository,
                       UserAssessmentRepository assessmentRepository,
                       TwilioService twilioService,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.otpTokenRepository = otpTokenRepository;
        this.assessmentRepository = assessmentRepository;
        this.twilioService = twilioService;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDto.SendOtpResponse sendOtp(String phoneNumber) {
        otpTokenRepository.invalidateAllByPhoneNumber(phoneNumber);

        String otp = generateOtp();

        OtpToken otpToken = OtpToken.builder()
                .phoneNumber(phoneNumber)
                .otp(otp)
                .expiresAt(LocalDateTime.now().plusMinutes(otpExpiryMinutes))
                .used(false)
                .attempts(0)
                .build();

        otpTokenRepository.save(otpToken);
        twilioService.sendOtp(phoneNumber, otp);

        log.info("OTP sent to phone: {}", maskPhone(phoneNumber));
        return new AuthDto.SendOtpResponse("OTP sent successfully", phoneNumber);
    }

    @Transactional
    public AuthDto.AuthResponse verifyOtp(String phoneNumber, String otp) {
        OtpToken otpToken = otpTokenRepository
                .findTopByPhoneNumberAndUsedFalseOrderByCreatedAtDesc(phoneNumber)
                .orElseThrow(() -> new OtpException("No active OTP found. Please request a new one."));

        if (otpToken.getAttempts() >= maxAttempts) {
            otpToken.setUsed(true);
            otpTokenRepository.save(otpToken);
            throw new OtpException("Too many failed attempts. Please request a new OTP.");
        }

        if (otpToken.isExpired()) {
            otpToken.setUsed(true);
            otpTokenRepository.save(otpToken);
            throw new OtpException("OTP has expired. Please request a new one.");
        }

        if (!otpToken.getOtp().equals(otp)) {
            otpToken.setAttempts(otpToken.getAttempts() + 1);
            otpTokenRepository.save(otpToken);
            int remaining = maxAttempts - otpToken.getAttempts();
            throw new OtpException("Invalid OTP. " + remaining + " attempt(s) remaining.");
        }

        otpToken.setUsed(true);
        otpTokenRepository.save(otpToken);

        boolean isNewUser = !userRepository.existsByPhoneNumber(phoneNumber);
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .phoneNumber(phoneNumber)
                                .verified(true)
                                .build()
                ));

        if (!user.isVerified()) {
            user.setVerified(true);
            userRepository.save(user);
        }

        String token = jwtService.generateToken(phoneNumber);
        log.info("User authenticated: {} | newUser: {}", maskPhone(phoneNumber), isNewUser);

        return new AuthDto.AuthResponse(token, phoneNumber, isNewUser, jwtService.getExpiration() / 1000);
    }

    // ── /me ──────────────────────────────────────────────────────────────────

    public AuthDto.MeResponse getMe(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Optional<UserAssessment> assessmentOpt = assessmentRepository.findByUserId(user.getId());

        if (assessmentOpt.isEmpty()) {
            // User hasn't completed assessment yet
            return new AuthDto.MeResponse(
                    user.getId(),
                    user.getPhoneNumber(),
                    user.isVerified(),
                    false,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null, null, null
            );
        }

        UserAssessment a = assessmentOpt.get();
        return new AuthDto.MeResponse(
                user.getId(),
                user.getPhoneNumber(),
                user.isVerified(),
                true,
                a.getFullName(),
                a.getAppPurpose(),
                a.getOccupation(),
                a.getOccupationDetail(),
                a.getIncomeSource(),
                a.getMonthlyIncome(),
                a.getMonthlySavings(),
                a.getPayFrequency(),
                a.getSpendingCategories(),
                a.getHasDebt(),
                a.getFinancialGoal(),
                a.getGoalAmount(),
                a.getGoalDeadline(),
                a.getExpenseTracking(),
                a.getRetirementAge(),
                a.getDependents(),
                a.getFinanceSentiment(),
                a.getHasEmergencyFund(),
                a.getEmergencyMonths(),
                a.getSpendingBehaviour(),
                a.getFinancialChallenges()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private String maskPhone(String phone) {
        if (phone.length() <= 4) return "****";
        return phone.substring(0, phone.length() - 4) + "****";
    }
}