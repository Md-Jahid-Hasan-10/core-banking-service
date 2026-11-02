package com.banking.cbs.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/** The account can cover the debit. The available balance is carried along for the screen. */
public record BalanceCheckedPayload(
        String transferId,
        String accountNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requiredAmount
) {}
