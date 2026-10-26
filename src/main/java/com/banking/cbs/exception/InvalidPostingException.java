package com.banking.cbs.exception;

public class InvalidPostingException extends RuntimeException {
    public InvalidPostingException(String message) {
        super(message);
    }
}