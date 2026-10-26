package com.banking.cbs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cbs")
public record CbsProperties(
        String feeAccountNumber,
        String feeAccountCurrency
) {}