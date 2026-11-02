package com.banking.cbs.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Asks the banking core whether an account can cover a debit.
 *
 * <p>This is the early check of the saga: it runs after the fee is known and
 * before the one-time password is requested, so the user is never sent a code
 * for a transaction that cannot succeed. The authoritative check still happens
 * when the money is posted, because the balance can change in between.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckBalanceCommand(
        String transferId,
        String commandId,
        String accountNumber,
        BigDecimal amount,
        BigDecimal fee
) {}
