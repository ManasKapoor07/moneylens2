package com.moneylens.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TwilioService {

    private static final Logger log = LoggerFactory.getLogger(TwilioService.class);

    @Value("${twilio.account-sid}")
    private String accountSid;

    @Value("${twilio.auth-token}")
    private String authToken;

    @Value("${twilio.phone-number}")
    private String fromNumber;

    @Value("${otp.mock:false}")
    private boolean mockMode;

    @PostConstruct
    public void init() {
        if (!mockMode) {
            Twilio.init(accountSid, authToken);
            log.info("Twilio initialized with number: {}", fromNumber);
        } else {
            log.info("TwilioService running in MOCK mode — OTPs will be logged to console");
        }
    }

    public void sendOtp(String toPhone, String otp) {
        if (mockMode) {
            log.info("========================================");
            log.info("  [MOCK OTP] Phone: {} | OTP: {}", toPhone, otp);
            log.info("========================================");
            return;
        }

        try {
            Message message = Message.creator(
                    new PhoneNumber(toPhone),
                    new PhoneNumber(fromNumber),
                    "Your MoneyLens OTP is: " + otp + ". Valid for 5 minutes. Do not share this."
            ).create();
            log.info("OTP sent to {} | SID: {}", toPhone, message.getSid());
        } catch (Exception e) {
            log.error("Failed to send OTP to {}: {}", toPhone, e.getMessage());
            throw new RuntimeException("Failed to send OTP. Please try again.");
        }
    }
}
