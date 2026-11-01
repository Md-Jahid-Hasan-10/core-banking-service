package com.banking.cbs.messaging.payload;

public record LedgerAccountCreationFailedPayload(String accountNumber, String reasonCode, String reason) {}