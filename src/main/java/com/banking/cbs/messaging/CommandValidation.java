package com.banking.cbs.messaging;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.regex.Pattern;

public final class CommandValidation {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int MAX_INTEGER_DIGITS = 15;

    private CommandValidation() {}

    public static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new InvalidEventException(field + " is missing or too long");
        }
        return value.trim();
    }

    public static BigDecimal positiveAmount(BigDecimal value, String field) {
        BigDecimal amount = money(value, field);
        if (amount.signum() <= 0) {
            throw new InvalidEventException(field + " must be greater than 0");
        }
        return amount;
    }

    /** A missing value counts as 0.00. */
    public static BigDecimal nonNegativeAmount(BigDecimal value, String field) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal amount = money(value, field);
        if (amount.signum() < 0) {
            throw new InvalidEventException(field + " cannot be negative");
        }
        return amount;
    }

    public static String oneOf(String value, String field, String... allowed) {
        if (value == null || Arrays.stream(allowed).noneMatch(value.trim()::equals)) {
            throw new InvalidEventException(field + " must be one of " + String.join(", ", allowed));
        }
        return value.trim();
    }

    /** The command id of a saga step is always "<transferId>:<STEP>". */
    public static String commandId(String value, String transferId, String step) {
        String expected = transferId + ":" + step;
        if (!expected.equals(value)) {
            throw new InvalidEventException("commandId must be " + expected);
        }
        return expected;
    }

    public static String currency(String value) {
        if (value == null || !CURRENCY.matcher(value.trim()).matches()) {
            throw new InvalidEventException("currency must be 3 capital letters");
        }
        return value.trim();
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            throw new InvalidEventException(field + " is missing");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new InvalidEventException(field + " allows at most 2 decimal places");
        }
        if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidEventException(field + " is too large");
        }
        return value.setScale(2);
    }
}