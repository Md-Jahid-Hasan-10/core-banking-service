package com.banking.cbs.messaging.payload;

public record LedgerAccountCreatedPayload(String accountNumber, String currency) {}