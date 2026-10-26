package com.banking.cbs.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PostingResponse(
        String transactionId,
        String referenceId,
        String status,
        BigDecimal amount,
        BigDecimal fee,
        LocalDateTime createdAt
) {}