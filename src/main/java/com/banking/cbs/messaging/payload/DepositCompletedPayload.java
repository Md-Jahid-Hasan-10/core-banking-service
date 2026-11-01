package com.banking.cbs.messaging.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

public record DepositCompletedPayload(
        String transferId,
        String accountNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount
) {}