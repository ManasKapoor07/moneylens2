package com.moneylens.exception;

public class UnsupportedBankException extends RuntimeException {
    public UnsupportedBankException(String message) {
        super(message);
    }
}