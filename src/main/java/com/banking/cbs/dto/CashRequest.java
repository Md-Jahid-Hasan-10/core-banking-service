package com.banking.cbs.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CashRequest(
        @NotBlank(message = "referenceId is required")
        @Size(max = 100, message = "referenceId must be at most 100 characters")
        String referenceId,

        @NotBlank(message = "accountNumber is required")
        @Size(max = 50, message = "accountNumber must be at most 50 characters")
        String accountNumber,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @DecimalMax(value = "1000000.00", message = "amount must be at most 1,000,000.00")
        @Digits(integer = 15, fraction = 2, message = "amount allows at most 2 decimal places")
        BigDecimal amount
) {}