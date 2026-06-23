package com.moneylens.exception;

public class DuplicateStatementException extends RuntimeException {
    public DuplicateStatementException(String message) {
        super(message);
    }
}