package com.banking.cbs.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_entry_account", columnList = "accountNumber"),
        @Index(name = "idx_entry_transaction", columnList = "transactionId")
})
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false, length = 50)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryDirection direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public static LedgerEntry of(UUID transactionId, String accountNumber, EntryDirection direction,
                                 BigDecimal amount, BigDecimal balanceAfter) {
        LedgerEntry entry = new LedgerEntry();
        entry.transactionId = transactionId;
        entry.accountNumber = accountNumber;
        entry.direction = direction;
        entry.amount = amount;
        entry.balanceAfter = balanceAfter;
        return entry;
    }

    public Long getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public String getAccountNumber() { return accountNumber; }
    public EntryDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}