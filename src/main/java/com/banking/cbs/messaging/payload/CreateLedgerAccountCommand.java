package com.banking.cbs.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateLedgerAccountCommand(
        String accountNumber,
        String currency,
        BigDecimal openingBalance
) {}