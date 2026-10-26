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
public class FeeAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeeAccountInitializer.class);

    private final LedgerAccountRepository accountRepository;
    private final CbsProperties properties;

    public FeeAccountInitializer(LedgerAccountRepository accountRepository, CbsProperties properties) {
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String number = properties.feeAccountNumber();
        if (!accountRepository.existsById(number)) {
            LedgerAccount fee = new LedgerAccount();
            fee.setAccountNumber(number);
            fee.setCurrency(properties.feeAccountCurrency());
            fee.setBalance(BigDecimal.ZERO.setScale(2));
            fee.setStatus(LedgerAccountStatus.ACTIVE);
            accountRepository.save(fee);
            log.info("Created fee income account {}", number);
        }
    }
}