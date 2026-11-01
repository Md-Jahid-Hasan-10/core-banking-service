package com.banking.cbs.messaging.payload;

public record FailurePayload(String transferId, String reasonCode, String reason) {}