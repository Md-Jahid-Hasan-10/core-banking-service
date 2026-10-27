package com.banking.cbs.service.impl;

import com.banking.cbs.dto.CashRequest;
import com.banking.cbs.config.CbsProperties;
import com.banking.cbs.dto.PostTransferRequest;
import com.banking.cbs.dto.PostingResponse;
import com.banking.cbs.entity.EntryDirection;
import com.banking.cbs.entity.LedgerAccount;
import com.banking.cbs.entity.LedgerAccountStatus;
import com.banking.cbs.entity.LedgerEntry;
import com.banking.cbs.entity.LedgerTransaction;
import com.banking.cbs.entity.TransactionType;
import com.banking.cbs.exception.AccountNotFoundException;
import com.banking.cbs.exception.DuplicateReferenceException;
import com.banking.cbs.exception.InsufficientFundsException;
import com.banking.cbs.exception.InvalidPostingException;
import com.banking.cbs.repository.LedgerAccountRepository;
import com.banking.cbs.repository.LedgerEntryRepository;
import com.banking.cbs.repository.LedgerTransactionRepository;
import com.banking.cbs.service.PostingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PostingServiceImpl implements PostingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final String CASH = "CASH";

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final CbsProperties properties;

    public PostingServiceImpl(LedgerAccountRepository accountRepository,
                              LedgerTransactionRepository transactionRepository,
                              LedgerEntryRepository entryRepository,
                              CbsProperties properties) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public PostingResponse postTransfer(PostTransferRequest request) {
        String reference = request.referenceId().trim();
        String from = request.fromAccountNumber().trim();
        String to = request.toAccountNumber().trim();
        BigDecimal amount = request.amount().setScale(2);
        BigDecimal fee = request.fee() == null ? ZERO : request.fee().setScale(2);

        if (from.equals(to)) {
            throw new InvalidPostingException("Cannot transfer to the same account");
        }

        // Idempotency: the same referenceId must never move money twice.
        Optional<LedgerTransaction> existing = transactionRepository.findByReferenceId(reference);
        if (existing.isPresent()) {
            return replay(existing.get(), from, to, amount, fee);
        }

        // Lock every account involved, always in the same order, to avoid deadlocks.
        String feeAccountNumber = properties.feeAccountNumber();
        Set<String> numbers = new TreeSet<>(Set.of(from, to));
        if (fee.signum() > 0) {
            numbers.add(feeAccountNumber);
        }
        Map<String, LedgerAccount> locked = accountRepository.findAllForUpdate(numbers).stream()
                .collect(Collectors.toMap(LedgerAccount::getAccountNumber, Function.identity()));

        LedgerAccount fromAccount = require(locked, from);
        LedgerAccount toAccount = require(locked, to);
        LedgerAccount feeAccount = fee.signum() > 0 ? require(locked, feeAccountNumber) : null;

        if (fromAccount.getStatus() != LedgerAccountStatus.ACTIVE
                || toAccount.getStatus() != LedgerAccountStatus.ACTIVE) {
            throw new InvalidPostingException("Account is not active");
        }
        if (!fromAccount.getCurrency().equals(toAccount.getCurrency())) {
            throw new InvalidPostingException("Currency mismatch between accounts");
        }
        if (feeAccount != null && !feeAccount.getCurrency().equals(fromAccount.getCurrency())) {
            throw new InvalidPostingException("Fee account currency does not match");
        }

        BigDecimal totalDebit = amount.add(fee);
        if (fromAccount.getBalance().compareTo(totalDebit) < 0) {
            throw new InsufficientFundsException("Insufficient balance");
        }

        // Apply the movement.
        fromAccount.setBalance(fromAccount.getBalance().subtract(totalDebit));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        if (feeAccount != null) {
            feeAccount.setBalance(feeAccount.getBalance().add(fee));
        }

        LedgerTransaction tx = new LedgerTransaction();
        tx.setId(UUID.randomUUID());
        tx.setReferenceId(reference);
        tx.setType(TransactionType.TRANSFER);
        tx.setFromAccountNumber(from);
        tx.setToAccountNumber(to);
        tx.setAmount(amount);
        tx.setFee(fee);
        tx.setStatus("POSTED");
        tx = transactionRepository.save(tx);

        entryRepository.save(LedgerEntry.of(
                tx.getId(), from, EntryDirection.DEBIT, totalDebit, fromAccount.getBalance()));
        entryRepository.save(LedgerEntry.of(
                tx.getId(), to, EntryDirection.CREDIT, amount, toAccount.getBalance()));
        if (feeAccount != null) {
            entryRepository.save(LedgerEntry.of(
                    tx.getId(), feeAccountNumber, EntryDirection.CREDIT, fee, feeAccount.getBalance()));
        }

        return toResponse(tx);
    }
    @Override
    @Transactional
    public PostingResponse postDeposit(CashRequest request) {
        return postCash(request, TransactionType.DEPOSIT);
    }

    @Override
    @Transactional
    public PostingResponse postWithdrawal(CashRequest request) {
        return postCash(request, TransactionType.WITHDRAWAL);
    }

    private PostingResponse postCash(CashRequest request, TransactionType type) {
        boolean deposit = type == TransactionType.DEPOSIT;
        String reference = request.referenceId().trim();
        String number = request.accountNumber().trim();
        BigDecimal amount = request.amount().setScale(2);

        // Idempotency: the same referenceId must never move money twice.
        Optional<LedgerTransaction> existing = transactionRepository.findByReferenceId(reference);
        if (existing.isPresent()) {
            LedgerTransaction previous = existing.get();
            String previousAccount = deposit
                    ? previous.getToAccountNumber()
                    : previous.getFromAccountNumber();
            boolean sameRequest = previous.getType() == type
                    && number.equals(previousAccount)
                    && amount.compareTo(previous.getAmount()) == 0;
            if (!sameRequest) {
                throw new DuplicateReferenceException("referenceId was already used with different details");
            }
            return toResponse(previous);
        }

        // Lock the account row so concurrent requests cannot overspend.
        LedgerAccount account = accountRepository.findAllForUpdate(Set.of(number)).stream()
                .findFirst()
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + number));

        if (account.getStatus() != LedgerAccountStatus.ACTIVE) {
            throw new InvalidPostingException("Account is not active");
        }

        if (deposit) {
            account.setBalance(account.getBalance().add(amount));
        } else {
            if (account.getBalance().compareTo(amount) < 0) {
                throw new InsufficientFundsException("Insufficient balance");
            }
            account.setBalance(account.getBalance().subtract(amount));
        }

        LedgerTransaction tx = new LedgerTransaction();
        tx.setId(UUID.randomUUID());
        tx.setReferenceId(reference);
        tx.setType(type);
        tx.setFromAccountNumber(deposit ? CASH : number);
        tx.setToAccountNumber(deposit ? number : CASH);
        tx.setAmount(amount);
        tx.setFee(ZERO);
        tx.setStatus("POSTED");
        LedgerTransaction saved = transactionRepository.save(tx);

        entryRepository.save(LedgerEntry.of(
                saved.getId(), number,
                deposit ? EntryDirection.CREDIT : EntryDirection.DEBIT,
                amount, account.getBalance()));

        return toResponse(saved);
    }

    private PostingResponse replay(LedgerTransaction existing, String from, String to,
                                   BigDecimal amount, BigDecimal fee) {
        boolean sameRequest = from.equals(existing.getFromAccountNumber())
                && to.equals(existing.getToAccountNumber())
                && amount.compareTo(existing.getAmount()) == 0
                && fee.compareTo(existing.getFee()) == 0;
        if (!sameRequest) {
            throw new DuplicateReferenceException("referenceId was already used with different details");
        }
        return toResponse(existing);
    }

    private LedgerAccount require(Map<String, LedgerAccount> accounts, String number) {
        LedgerAccount account = accounts.get(number);
        if (account == null) {
            throw new AccountNotFoundException("Account not found: " + number);
        }
        return account;
    }

    private PostingResponse toResponse(LedgerTransaction tx) {
        return new PostingResponse(
                tx.getId().toString(),
                tx.getReferenceId(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getFee(),
                tx.getCreatedAt()
        );
    }
}