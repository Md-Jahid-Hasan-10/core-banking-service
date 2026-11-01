package com.banking.cbs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** A transfer's money leaves transit exactly once: either DEPOSITED or REVERSED. */
@Entity
@Table(name = "transit_settlements")
public class TransitSettlement {

    @Id
    @Column(length = 80)
    private String transferId;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TransitSettlement() {}

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getTransferId() { return transferId; }
    public String getOutcome() { return outcome; }
    public Instant getCreatedAt() { return createdAt; }
}