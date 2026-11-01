package com.banking.cbs.messaging;

import com.banking.cbs.config.CbsProperties;
import com.banking.cbs.dto.CreateLedgerAccountRequest;
import com.banking.cbs.entity.CommandResult;
import com.banking.cbs.entity.EntryDirection;
import com.banking.cbs.entity.LedgerAccount;
import com.banking.cbs.entity.LedgerAccountStatus;
import com.banking.cbs.entity.LedgerEntry;
import com.banking.cbs.entity.LedgerTransaction;
import com.banking.cbs.entity.TransactionType;
import com.banking.cbs.messaging.payload.CreateLedgerAccountCommand;
import com.banking.cbs.messaging.payload.DepositCommand;
import com.banking.cbs.messaging.payload.DepositCompletedPayload;
import com.banking.cbs.messaging.payload.FailurePayload;
import com.banking.cbs.messaging.payload.LedgerAccountCreatedPayload;
import com.banking.cbs.messaging.payload.LedgerAccountCreationFailedPayload;
import com.banking.cbs.messaging.payload.ReversalCommand;
import com.banking.cbs.messaging.payload.ReversalCompletedPayload;
import com.banking.cbs.messaging.payload.WithdrawCommand;
import com.banking.cbs.messaging.payload.WithdrawCompletedPayload;
import com.banking.cbs.repository.CommandResultRepository;
import com.banking.cbs.repository.LedgerAccountRepository;
import com.banking.cbs.repository.LedgerEntryRepository;
import com.banking.cbs.repository.LedgerTransactionRepository;
import com.banking.cbs.repository.TransitSettlementRepository;
import com.banking.cbs.service.LedgerAccountService;
import io.micrometer.core.instrument.MeterRegistry;
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
public class SagaStepService {

    private static final String TRANSIT = "TRANSIT";
    private static final String CASH = "CASH";
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CASH", "TRANSIT", "FEE-INCOME", "TRANSFER-TRANSIT");

    /** Why a step was refused. A rejection never changes the ledger. */
    private record Rejection(String code, String reason) {}

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final CommandResultRepository results;
    private final TransitSettlementRepository settlements;
    private final LedgerAccountService accountService;
    private final EventPublisher events;
    private final CbsProperties properties;
    private final MeterRegistry meters;

    public SagaStepService(LedgerAccountRepository accounts,
                           LedgerTransactionRepository transactions,
                           LedgerEntryRepository entries,
                           CommandResultRepository results,
                           TransitSettlementRepository settlements,
                           LedgerAccountService accountService,
                           EventPublisher events,
                           CbsProperties properties,
                           MeterRegistry meters) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.results = results;
        this.settlements = settlements;
        this.accountService = accountService;
        this.events = events;
        this.properties = properties;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ Withdraw

    @Transactional
    public void withdraw(EventEnvelope command, WithdrawCommand cmd) {
        String transferId = CommandValidation.text(cmd.transferId(), "transferId", 80);
        String commandId = CommandValidation.commandId(cmd.commandId(), transferId, "WITHDRAW");
        String number = CommandValidation.text(cmd.accountNumber(), "accountNumber", 50);
        BigDecimal amount = CommandValidation.positiveAmount(cmd.amount(), "amount");
        BigDecimal fee = CommandValidation.nonNegativeAmount(cmd.fee(), "fee");
        String destination = CommandValidation.oneOf(cmd.destination(), "destination", TRANSIT, CASH);
        boolean toTransit = TRANSIT.equals(destination);
        if (!toTransit && fee.signum() > 0) {
            throw new InvalidEventException("A withdrawal to CASH cannot carry a fee");
        }

        if (results.existsById(commandId)) {
            count("withdraw", "duplicate");
            return;
        }

        Rejection rejection = applyWithdraw(commandId, number, amount, fee, toTransit);
        if (rejection == null) {
            results.save(CommandResult.completed(commandId));
            events.publish(TopicNames.CBS_EVENTS, transferId, "WithdrawCompleted", transferId,
                    command.eventId(), new WithdrawCompletedPayload(transferId, number, amount, fee));
            count("withdraw", "completed");
        } else {
            results.save(CommandResult.failed(commandId, rejection.code()));
            events.publish(TopicNames.CBS_EVENTS, transferId, "WithdrawFailed", transferId,
                    command.eventId(), new FailurePayload(transferId, rejection.code(), rejection.reason()));
            count("withdraw", "failed");
        }
    }

    private Rejection applyWithdraw(String commandId, String number, BigDecimal amount,
                                    BigDecimal fee, boolean toTransit) {
        boolean hasFee = fee.signum() > 0;
        String transitNumber = properties.transitAccountNumber();
        String feeNumber = properties.feeAccountNumber();

        Set<String> numbers = new TreeSet<>();
        numbers.add(number);
        if (toTransit) {
            numbers.add(transitNumber);
        }
        if (hasFee) {
            numbers.add(feeNumber);
        }
        Map<String, LedgerAccount> locked = lock(numbers);

        LedgerAccount account = locked.get(number);
        if (account == null) {
            return new Rejection("ACCOUNT_NOT_FOUND", "Account not found: " + number);
        }
        if (account.getStatus() != LedgerAccountStatus.ACTIVE) {
            return new Rejection("ACCOUNT_INACTIVE", "Account is not active");
        }

        LedgerAccount transit = toTransit ? locked.get(transitNumber) : null;
        LedgerAccount feeAccount = hasFee ? locked.get(feeNumber) : null;
        if ((toTransit && transit == null) || (hasFee && feeAccount == null)) {
            return new Rejection("SYSTEM_ACCOUNT_MISSING", "A system ledger account is missing");
        }
        if ((transit != null && !transit.getCurrency().equals(account.getCurrency()))
                || (feeAccount != null && !feeAccount.getCurrency().equals(account.getCurrency()))) {
            return new Rejection("SYSTEM_ACCOUNT_MISSING", "System account currency does not match");
        }

        BigDecimal total = amount.add(fee);
        if (account.getBalance().compareTo(total) < 0) {
            return new Rejection("INSUFFICIENT_FUNDS", "Insufficient balance");
        }

        // All checks passed. Only now does the ledger change.
        account.setBalance(account.getBalance().subtract(total));
        if (transit != null) {
            transit.setBalance(transit.getBalance().add(amount));
        }
        if (feeAccount != null) {
            feeAccount.setBalance(feeAccount.getBalance().add(fee));
        }

        LedgerTransaction tx = newTransaction(commandId, TransactionType.WITHDRAWAL,
                number, toTransit ? TRANSIT : CASH, amount, fee);
        transactions.save(tx);

        entries.save(LedgerEntry.of(tx.getId(), number, EntryDirection.DEBIT, total, account.getBalance()));
        if (transit != null) {
            entries.save(LedgerEntry.of(tx.getId(), transitNumber, EntryDirection.CREDIT,
                    amount, transit.getBalance()));
        }
        if (feeAccount != null) {
            entries.save(LedgerEntry.of(tx.getId(), feeNumber, EntryDirection.CREDIT,
                    fee, feeAccount.getBalance()));
        }
        return null;
    }

    // ------------------------------------------------------------------- Deposit

    @Transactional
    public void deposit(EventEnvelope command, DepositCommand cmd) {
        String transferId = CommandValidation.text(cmd.transferId(), "transferId", 80);
        String commandId = CommandValidation.commandId(cmd.commandId(), transferId, "DEPOSIT");
        String number = CommandValidation.text(cmd.accountNumber(), "accountNumber", 50);
        BigDecimal amount = CommandValidation.positiveAmount(cmd.amount(), "amount");
        String source = CommandValidation.oneOf(cmd.source(), "source", TRANSIT, CASH);
        boolean fromTransit = TRANSIT.equals(source);

        if (results.existsById(commandId)) {
            count("deposit", "duplicate");
            return;
        }

        Rejection rejection = applyDeposit(commandId, transferId, number, amount, fromTransit);
        if (rejection == null) {
            results.save(CommandResult.completed(commandId));
            events.publish(TopicNames.CBS_EVENTS, transferId, "DepositCompleted", transferId,
                    command.eventId(), new DepositCompletedPayload(transferId, number, amount));
            count("deposit", "completed");
        } else {
            results.save(CommandResult.failed(commandId, rejection.code()));
            events.publish(TopicNames.CBS_EVENTS, transferId, "DepositFailed", transferId,
                    command.eventId(), new FailurePayload(transferId, rejection.code(), rejection.reason()));
            count("deposit", "failed");
        }
    }

    private Rejection applyDeposit(String commandId, String transferId, String number,
                                   BigDecimal amount, boolean fromTransit) {
        String transitNumber = properties.transitAccountNumber();

        if (fromTransit) {
            Optional<LedgerTransaction> withdrawal = findWithdrawal(transferId);
            if (withdrawal.isEmpty()) {
                return new Rejection("NO_WITHDRAWAL",
                        "No completed withdrawal found for transfer " + transferId);
            }
            if (withdrawal.get().getAmount().compareTo(amount) != 0) {
                return new Rejection("AMOUNT_MISMATCH", "Amount does not match the withdrawal");
            }
        }

        Set<String> numbers = new TreeSet<>();
        numbers.add(number);
        if (fromTransit) {
            numbers.add(transitNumber);
        }
        Map<String, LedgerAccount> locked = lock(numbers);

        LedgerAccount account = locked.get(number);
        if (account == null) {
            return new Rejection("ACCOUNT_NOT_FOUND", "Account not found: " + number);
        }
        if (account.getStatus() != LedgerAccountStatus.ACTIVE) {
            return new Rejection("ACCOUNT_INACTIVE", "Account is not active");
        }

        LedgerAccount transit = fromTransit ? locked.get(transitNumber) : null;
        if (fromTransit) {
            if (transit == null || !transit.getCurrency().equals(account.getCurrency())) {
                return new Rejection("SYSTEM_ACCOUNT_MISSING", "The transit account is missing or has another currency");
            }
            if (transit.getBalance().compareTo(amount) < 0) {
                return new Rejection("TRANSIT_SHORTFALL", "The transit account holds less than this amount");
            }
            // Claim the transfer last, after every check, so a rejection leaves no trace.
            if (settlements.insertIfAbsent(transferId, "DEPOSITED") == 0) {
                return new Rejection("ALREADY_SETTLED", "This transfer was already deposited or reversed");
            }
        }

        account.setBalance(account.getBalance().add(amount));
        if (transit != null) {
            transit.setBalance(transit.getBalance().subtract(amount));
        }

        LedgerTransaction tx = newTransaction(commandId, TransactionType.DEPOSIT,
                fromTransit ? TRANSIT : CASH, number, amount, BigDecimal.ZERO.setScale(2));
        transactions.save(tx);

        entries.save(LedgerEntry.of(tx.getId(), number, EntryDirection.CREDIT, amount, account.getBalance()));
        if (transit != null) {
            entries.save(LedgerEntry.of(tx.getId(), transitNumber, EntryDirection.DEBIT,
                    amount, transit.getBalance()));
        }
        return null;
    }

    // ------------------------------------------------------------------ Reversal

    @Transactional
    public void reversal(EventEnvelope command, ReversalCommand cmd) {
        String transferId = CommandValidation.text(cmd.transferId(), "transferId", 80);
        String commandId = CommandValidation.commandId(cmd.commandId(), transferId, "REVERSAL");
        String number = CommandValidation.text(cmd.accountNumber(), "accountNumber", 50);
        BigDecimal amount = CommandValidation.positiveAmount(cmd.amount(), "amount");
        BigDecimal fee = CommandValidation.nonNegativeAmount(cmd.fee(), "fee");

        if (results.existsById(commandId)) {
            count("reversal", "duplicate");
            return;
        }

        Rejection rejection = applyReversal(commandId, transferId, number, amount, fee);
        if (rejection == null) {
            BigDecimal refunded = amount.add(fee);
            results.save(CommandResult.completed(commandId));
            events.publish(TopicNames.CBS_EVENTS, transferId, "ReversalCompleted", transferId,
                    command.eventId(), new ReversalCompletedPayload(transferId, number, refunded));
            count("reversal", "completed");
        } else {
            results.save(CommandResult.failed(commandId, rejection.code()));
            events.publish(TopicNames.CBS_EVENTS, transferId, "ReversalFailed", transferId,
                    command.eventId(), new FailurePayload(transferId, rejection.code(), rejection.reason()));
            count("reversal", "failed");
        }
    }

    private Rejection applyReversal(String commandId, String transferId, String number,
                                    BigDecimal amount, BigDecimal fee) {
        Optional<LedgerTransaction> found = findWithdrawal(transferId);
        if (found.isEmpty()) {
            return new Rejection("NO_WITHDRAWAL", "No completed withdrawal found for transfer " + transferId);
        }

        // The ledger decides what is refunded and to whom, never the message.
        LedgerTransaction withdrawal = found.get();
        if (!number.equals(withdrawal.getFromAccountNumber())
                || withdrawal.getAmount().compareTo(amount) != 0
                || withdrawal.getFee().compareTo(fee) != 0) {
            return new Rejection("MISMATCH", "The reversal does not match the original withdrawal");
        }

        boolean hasFee = fee.signum() > 0;
        String transitNumber = properties.transitAccountNumber();
        String feeNumber = properties.feeAccountNumber();

        Set<String> numbers = new TreeSet<>();
        numbers.add(number);
        numbers.add(transitNumber);
        if (hasFee) {
            numbers.add(feeNumber);
        }
        Map<String, LedgerAccount> locked = lock(numbers);

        // A refund must work even when the account was frozen in the meantime, so the status is ignored.
        LedgerAccount account = locked.get(number);
        if (account == null) {
            return new Rejection("ACCOUNT_NOT_FOUND", "Account not found: " + number);
        }
        LedgerAccount transit = locked.get(transitNumber);
        LedgerAccount feeAccount = hasFee ? locked.get(feeNumber) : null;
        if (transit == null || (hasFee && feeAccount == null)) {
            return new Rejection("SYSTEM_ACCOUNT_MISSING", "A system ledger account is missing");
        }
        if (transit.getBalance().compareTo(amount) < 0
                || (feeAccount != null && feeAccount.getBalance().compareTo(fee) < 0)) {
            return new Rejection("TRANSIT_SHORTFALL", "A system account holds less than the refund needs");
        }
        if (settlements.insertIfAbsent(transferId, "REVERSED") == 0) {
            return new Rejection("ALREADY_SETTLED", "This transfer was already deposited or reversed");
        }

        BigDecimal refund = amount.add(fee);
        account.setBalance(account.getBalance().add(refund));
        transit.setBalance(transit.getBalance().subtract(amount));
        if (feeAccount != null) {
            feeAccount.setBalance(feeAccount.getBalance().subtract(fee));
        }

        LedgerTransaction tx = newTransaction(commandId, TransactionType.REVERSAL,
                TRANSIT, number, amount, fee);
        transactions.save(tx);

        entries.save(LedgerEntry.of(tx.getId(), number, EntryDirection.CREDIT, refund, account.getBalance()));
        entries.save(LedgerEntry.of(tx.getId(), transitNumber, EntryDirection.DEBIT,
                amount, transit.getBalance()));
        if (feeAccount != null) {
            entries.save(LedgerEntry.of(tx.getId(), feeNumber, EntryDirection.DEBIT,
                    fee, feeAccount.getBalance()));
        }
        return null;
    }

    // ------------------------------------------------------- CreateLedgerAccount

    @Transactional
    public void createLedgerAccount(EventEnvelope command, CreateLedgerAccountCommand cmd) {
        String number = CommandValidation.text(cmd.accountNumber(), "accountNumber", 50);
        String currency = CommandValidation.currency(cmd.currency());
        BigDecimal opening = CommandValidation.nonNegativeAmount(cmd.openingBalance(), "openingBalance");

        if (RESERVED_NAMES.contains(number)) {
            events.publish(TopicNames.CBS_EVENTS, number, "LedgerAccountCreationFailed", number,
                    command.eventId(),
                    new LedgerAccountCreationFailedPayload(number, "RESERVED_NAME", "This account number is reserved"));
            count("create", "failed");
            return;
        }

        Optional<LedgerAccount> existing = accounts.findById(number);
        if (existing.isPresent()) {
            // Creating twice is harmless: answer the same way again.
            if (existing.get().getCurrency().equals(currency)) {
                events.publish(TopicNames.CBS_EVENTS, number, "LedgerAccountCreated", number,
                        command.eventId(), new LedgerAccountCreatedPayload(number, currency));
                count("create", "duplicate");
            } else {
                events.publish(TopicNames.CBS_EVENTS, number, "LedgerAccountCreationFailed", number,
                        command.eventId(),
                        new LedgerAccountCreationFailedPayload(number, "CURRENCY_CONFLICT",
                                "The account exists with another currency"));
                count("create", "failed");
            }
            return;
        }

        accountService.create(new CreateLedgerAccountRequest(number, currency, opening));
        events.publish(TopicNames.CBS_EVENTS, number, "LedgerAccountCreated", number,
                command.eventId(), new LedgerAccountCreatedPayload(number, currency));
        count("create", "completed");
    }

    // ------------------------------------------------------------------- helpers

    /** Locks the accounts in alphabetical order, the same order everywhere, so transfers cannot deadlock. */
    private Map<String, LedgerAccount> lock(Set<String> numbers) {
        return accounts.findAllForUpdate(numbers).stream()
                .collect(Collectors.toMap(LedgerAccount::getAccountNumber, Function.identity()));
    }

    private Optional<LedgerTransaction> findWithdrawal(String transferId) {
        return transactions.findByReferenceId(transferId + ":WITHDRAW")
                .filter(tx -> tx.getType() == TransactionType.WITHDRAWAL)
                .filter(tx -> TRANSIT.equals(tx.getToAccountNumber()));
    }

    private LedgerTransaction newTransaction(String referenceId, TransactionType type,
                                             String from, String to, BigDecimal amount, BigDecimal fee) {
        LedgerTransaction tx = new LedgerTransaction();
        tx.setId(UUID.randomUUID());
        tx.setReferenceId(referenceId);
        tx.setType(type);
        tx.setFromAccountNumber(from);
        tx.setToAccountNumber(to);
        tx.setAmount(amount);
        tx.setFee(fee);
        tx.setStatus("POSTED");
        return tx;
    }

    private void count(String step, String result) {
        meters.counter("cbs.steps", "step", step, "result", result).increment();
    }
}