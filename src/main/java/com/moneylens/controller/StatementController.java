package com.moneylens.controller;

import com.moneylens.dto.StatementUploadResponse;
import com.moneylens.entity.BankStatement;
import com.moneylens.entity.Transaction;
import com.moneylens.service.StatementService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Statement endpoints.
 *
 * POST /api/v1/statements/upload          → upload a PDF (any date range)
 * GET  /api/v1/statements                 → list all uploaded statements
 * GET  /api/v1/statements/transactions    → transactions filtered by year+month
 */
@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    /**
     * Upload a bank statement PDF.
     *
     * The PDF can cover any date range — 1 month, 3 months, 6 months, a full year.
     * One StatementProfile is created per calendar month found in the PDF.
     * The OverallProfile is refreshed once after all profiles are created.
     *
     * Multipart fields:
     *   file     — the PDF (required)
     *   password — PDF password if protected (optional)
     *              For HDFC: usually DOB (DDMMYYYY) or last 4 digits of mobile
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StatementUploadResponse> uploadStatement(
            @AuthenticationPrincipal String phoneNumber,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "bank",     required = false) String bank) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            return ResponseEntity.badRequest().build();
        }

        StatementUploadResponse response =
                statementService.uploadStatement(phoneNumber, file, password, bank);

        return ResponseEntity.ok(response);
    }

    /**
     * List all uploaded statements for the authenticated user, newest first.
     * Returns statement metadata only (no transactions).
     * The frontend uses this to show upload history.
     */
    @GetMapping
    public ResponseEntity<List<BankStatement>> getStatements(
            @AuthenticationPrincipal String phoneNumber) {

        return ResponseEntity.ok(statementService.getStatements(phoneNumber));
    }

    /**
     * Get transactions for a specific month.
     *
     * Query params:
     *   year  — e.g. 2026 (required if month provided)
     *   month — e.g. 5 for May (required if year provided)
     *
     * If neither is provided, returns all transactions newest first.
     */
    @GetMapping("/transactions")
    public ResponseEntity<List<Transaction>> getTransactions(
            @AuthenticationPrincipal String phoneNumber,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        return ResponseEntity.ok(
                statementService.getTransactions(phoneNumber, year, month));
    }
}