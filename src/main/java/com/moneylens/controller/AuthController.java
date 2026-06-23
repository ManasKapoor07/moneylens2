package com.moneylens.controller;

import com.moneylens.dto.AuthDto;
import com.moneylens.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/send-otp")
    public ResponseEntity<AuthDto.SendOtpResponse> sendOtp(
            @Valid @RequestBody AuthDto.SendOtpRequest request) {
        return ResponseEntity.ok(authService.sendOtp(request.phoneNumber()));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<AuthDto.AuthResponse> verifyOtp(
            @Valid @RequestBody AuthDto.VerifyOtpRequest request) {
        return ResponseEntity.ok(authService.verifyOtp(request.phoneNumber(), request.otp()));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDto.MeResponse> me(
            @AuthenticationPrincipal String phoneNumber) {
        return ResponseEntity.ok(authService.getMe(phoneNumber));
    }
}