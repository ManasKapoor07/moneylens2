package com.moneylens.exception;

import com.moneylens.dto.AuthDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateStatementException.class)
    public ResponseEntity<AuthDto.ErrorResponse> handleDuplicate(DuplicateStatementException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AuthDto.ErrorResponse("DUPLICATE_STATEMENT", ex.getMessage(), 409));
    }

    @ExceptionHandler(PdfExtractionException.class)
    public ResponseEntity<AuthDto.ErrorResponse> handlePdfError(PdfExtractionException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new AuthDto.ErrorResponse("PDF_EXTRACTION_FAILED", ex.getMessage(), 422));
    }

    @ExceptionHandler(UnsupportedBankException.class)
    public ResponseEntity<AuthDto.ErrorResponse> handleUnsupportedBank(UnsupportedBankException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new AuthDto.ErrorResponse("UNSUPPORTED_BANK", ex.getMessage(), 422));
    }

    @ExceptionHandler(OtpException.class)
    public ResponseEntity<AuthDto.ErrorResponse> handleOtp(OtpException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AuthDto.ErrorResponse("OTP_ERROR", ex.getMessage(), 400));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AuthDto.ErrorResponse> handleGeneric(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AuthDto.ErrorResponse("INTERNAL_ERROR", ex.getMessage(), 500));
    }
}