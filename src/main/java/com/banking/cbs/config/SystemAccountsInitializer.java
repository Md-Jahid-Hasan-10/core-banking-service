package com.banking.cbs.config;

import com.banking.cbs.entity.LedgerAccount;
import com.banking.cbs.entity.LedgerAccountStatus;
import com.banking.cbs.repository.LedgerAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SystemAccountsInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemAccountsInitializer.class);

    private final LedgerAccountRepository accountRepository;
    private final CbsProperties properties;

    public SystemAccountsInitializer(LedgerAccountRepository accountRepository, CbsProperties properties) {
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensure(properties.feeAccountNumber(), properties.feeAccountCurrency());
        ensure(properties.transitAccountNumber(), properties.transitAccountCurrency());
    }

    private void ensure(String number, String currency) {
        if (!accountRepository.existsById(number)) {
            LedgerAccount account = new LedgerAccount();
            account.setAccountNumber(number);
            account.setCurrency(currency);
            account.setBalance(BigDecimal.ZERO.setScale(2));
            account.setStatus(LedgerAccountStatus.ACTIVE);
            accountRepository.save(account);
            log.info("Created system account {}", number);
        }
    }
}