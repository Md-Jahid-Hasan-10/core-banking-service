package com.banking.cbs.exception;

public class DuplicateReferenceException extends RuntimeException {
    public DuplicateReferenceException(String message) {
        super(message);
    }
}