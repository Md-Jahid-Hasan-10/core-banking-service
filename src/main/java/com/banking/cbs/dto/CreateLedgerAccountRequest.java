package com.banking.cbs.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateLedgerAccountRequest(
        @NotBlank(message = "accountNumber is required")
        @Size(max = 50, message = "accountNumber must be at most 50 characters")
        String accountNumber,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter code such as USD")
        String currency,

        @DecimalMin(value = "0.00", message = "openingBalance cannot be negative")
        @Digits(integer = 15, fraction = 2, message = "openingBalance allows at most 2 decimal places")
        BigDecimal openingBalance
) {}