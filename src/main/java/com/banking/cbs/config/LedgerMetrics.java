package com.banking.cbs.config;

import com.banking.cbs.repository.LedgerAccountRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class LedgerMetrics {

    public LedgerMetrics(MeterRegistry registry, LedgerAccountRepository accounts, CbsProperties properties) {
        Gauge.builder("cbs.transit.balance",
                        () -> balanceOf(accounts, properties.transitAccountNumber()))
                .description("Money currently in flight between a withdrawal and its deposit or reversal")
                .register(registry);

        Gauge.builder("cbs.fee.income.balance",
                        () -> balanceOf(accounts, properties.feeAccountNumber()))
                .description("Fees collected so far")
                .register(registry);
    }

    private static double balanceOf(LedgerAccountRepository accounts, String accountNumber) {
        return accounts.findById(accountNumber)
                .map(account -> account.getBalance().doubleValue())
                .orElse(0.0);
    }
}