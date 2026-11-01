package com.banking.cbs.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReversalCommand(
        String transferId,
        String commandId,
        String accountNumber,
        BigDecimal amount,
        BigDecimal fee
) {}