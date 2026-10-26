package com.banking.cbs.service.impl;

import com.banking.cbs.dto.BalanceResponse;
import com.banking.cbs.dto.CreateLedgerAccountRequest;
import com.banking.cbs.entity.EntryDirection;
import com.banking.cbs.entity.LedgerAccount;
import com.banking.cbs.entity.LedgerAccountStatus;
import com.banking.cbs.entity.LedgerEntry;
import com.banking.cbs.entity.LedgerTransaction;
import com.banking.cbs.entity.TransactionType;
import com.banking.cbs.exception.AccountAlreadyExistsException;
import com.banking.cbs.exception.AccountNotFoundException;
import com.banking.cbs.repository.LedgerAccountRepository;
import com.banking.cbs.repository.LedgerEntryRepository;
import com.banking.cbs.repository.LedgerTransactionRepository;
import com.banking.cbs.service.LedgerAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerAccountServiceImpl implements LedgerAccountService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerAccountServiceImpl(LedgerAccountRepository accountRepository,
                                    LedgerTransactionRepository transactionRepository,
                                    LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @Override
    @Transactional
    public BalanceResponse create(CreateLedgerAccountRequest request) {
        String number = request.accountNumber().trim();

        if (accountRepository.existsById(number)) {
            throw new AccountAlreadyExistsException("Ledger account already exists: " + number);
        }

        BigDecimal opening = request.openingBalance() == null
                ? BigDecimal.ZERO.setScale(2)
                : request.openingBalance().setScale(2);

        LedgerAccount account = new LedgerAccount();
        account.setAccountNumber(number);
        account.setCurrency(request.currency());
        account.setBalance(opening);
        account.setStatus(LedgerAccountStatus.ACTIVE);
        accountRepository.save(account);

        if (opening.signum() > 0) {
            LedgerTransaction deposit = new LedgerTransaction();
            deposit.setId(UUID.randomUUID());
            deposit.setReferenceId("OPEN-" + number);
            deposit.setType(TransactionType.DEPOSIT);
            deposit.setFromAccountNumber(null);
            deposit.setToAccountNumber(number);
            deposit.setAmount(opening);
            deposit.setFee(BigDecimal.ZERO.setScale(2));
            deposit.setStatus("POSTED");
            transactionRepository.save(deposit);

            entryRepository.save(LedgerEntry.of(
                    deposit.getId(), number, EntryDirection.CREDIT, opening, opening));
        }

        return toResponse(account);
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountNumber) {
        return accountRepository.findById(accountNumber.trim())
                .map(this::toResponse)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BalanceResponse> getBalances(List<String> accountNumbers) {
        if (accountNumbers == null || accountNumbers.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = accountNumbers.stream().map(String::trim).toList();
        return accountRepository.findByAccountNumberIn(cleaned).stream()
                .map(this::toResponse)
                .toList();
    }

    private BalanceResponse toResponse(LedgerAccount account) {
        return new BalanceResponse(account.getAccountNumber(), account.getBalance(), account.getCurrency());
    }
}