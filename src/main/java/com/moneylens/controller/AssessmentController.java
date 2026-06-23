package com.moneylens.controller;

import com.moneylens.dto.AssessmentDto;
import com.moneylens.service.AssessmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/assessment")
public class AssessmentController {

    private final AssessmentService assessmentService;

    public AssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    /**
     * POST /api/v1/assessment
     * Header: Authorization: Bearer <jwt>
     */
    @PostMapping
    public ResponseEntity<AssessmentDto.AssessmentResponse> saveAssessment(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody AssessmentDto.SaveAssessmentRequest request) {

        AssessmentDto.AssessmentResponse response = assessmentService.saveAssessment(phoneNumber, request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/assessment/status
     * Header: Authorization: Bearer <jwt>
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAssessmentStatus(
            @AuthenticationPrincipal String phoneNumber) {

        boolean completed = assessmentService.hasCompletedAssessment(phoneNumber);
        return ResponseEntity.ok(Map.of("assessmentCompleted", completed));
    }
}