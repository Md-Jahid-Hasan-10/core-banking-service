package com.banking.cbs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** One row for every command CBS has answered, successful or not. */
@Entity
@Table(name = "command_results")
public class CommandResult {

    @Id
    @Column(length = 160)
    private String commandId;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(length = 50)
    private String reasonCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected CommandResult() {}

    public static CommandResult completed(String commandId) {
        CommandResult result = new CommandResult();
        result.commandId = commandId;
        result.outcome = "COMPLETED";
        return result;
    }

    public static CommandResult failed(String commandId, String reasonCode) {
        CommandResult result = new CommandResult();
        result.commandId = commandId;
        result.outcome = "FAILED";
        result.reasonCode = reasonCode;
        return result;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getCommandId() { return commandId; }
    public String getOutcome() { return outcome; }
    public String getReasonCode() { return reasonCode; }
    public Instant getCreatedAt() { return createdAt; }
}