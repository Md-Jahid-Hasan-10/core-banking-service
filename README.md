# Core Banking Service

> The authoritative double-entry ledger of the bank: it owns the ledger tables, it is the only component allowed to change a balance, and it runs on port `8084` (management port `18084`).

## Table of Contents

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

`core-banking-service` is the **authoritative double-entry ledger** of the bank. It owns the
`ledger_accounts`, `ledger_entries` and `ledger_transactions` tables and is the only component allowed to
change a balance. Every money movement is recorded as a `ledger_transactions` row (with a unique
`referenceId`) plus one or more `ledger_entries` rows — a `DEBIT` on one account and a `CREDIT` on
another — so that the sum of the debits equals the sum of the credits for every transaction.

It also owns the **system accounts**: `FEE-INCOME` (where fees accumulate) and `TRANSFER-TRANSIT` (the
in-flight money of a transfer, between the debit at the sender and the credit at the receiver).

The service exposes a small internal REST API under `/internal/cbs/*` for the other services (balance
lookups and the legacy synchronous postings) and consumes commands from the Kafka topic `cbs.commands`,
answering with facts on `cbs.events`. It is a pure ledger: it never sends e-mails, never calculates
fees, never knows about users, and it never decides on its own that a transaction may go ahead — it only
accepts or refuses what it is told to post.

What it does **not** own:

- User identity and authentication. There is no user table and no Spring Security dependency.
- Fees. The fee amount arrives in the command; Charge Fee Service (`charge-fee-service`, port 8085) decides it.
- The one-time password. OTP Service (`otp-service`, port 8086) owns it.
- Account ownership and account status from the customer's point of view. Account Service
  (`account-service`, port 8081) owns that; the ledger only knows `ACTIVE` and `FROZEN`.

Every command it answers is recorded in `command_results`, so a command that is delivered twice never
moves money twice.

## Architecture At A Glance

```
  account-service (8081) ─┐
                          │  POST /internal/cbs/accounts, GET .../balance
  fund-transfer-service   │  POST /internal/cbs/{transfers,deposits,withdrawals}
       (8082) ────────────┘
                                  │
                                  ▼
                    ┌──────────────────────────────┐
                    │   core-banking-service (8084) │
                    │   • InternalCbsController      │
                    │     /internal/cbs/*            │
                    │   • CbsCommandsConsumer        │
                    │     cbs.commands -> cbs.events │
                    └──────────────┬───────────────┘
                                   │
                 ┌─────────────────┼─────────────────┐
                 ▼                 ▼                 ▼
        PostgreSQL cbs_db    Kafka cbs.events   Kafka cbs.commands.DLT
        (ledger_accounts,    (facts out)        (poison messages)
         ledger_entries,
         ledger_transactions,
         command_results,
         outbox_events,
         transit_settlements)
```

| Depends on | Depended on by |
|---|---|
| PostgreSQL `cbs_db` (Hibernate-created schema) | `fund-transfer-service` (`fund-transfer-service`, port 8082) via `app.services.cbs` |
| Kafka `cbs.commands` (consumer group `core-banking-service`) | `account-service` (`account-service`, port 8081) via `app.cbs.base-url` |
| Eureka discovery (`discovery-service`, port 8761) | `api-gateway` — but the gateway **blocks** `/internal/**` with `404 "Not found"` |
| `event-bus-service` (port 8090) creates the topics | |

## HTTP API

There is exactly **one** controller in the service (`InternalCbsController`); there are no public
`/api/**` endpoints.

**Authentication:** the service has no Spring Security dependency and no filter of its own. The
`/internal/cbs/*` endpoints are **unauthenticated** and are expected to be reached only over the
internal network / service discovery (`lb://core-banking-service`), never through the public gateway.
The API gateway explicitly blocks `/internal/**` with a `404 "Not found"`. **No custom headers are
required** — the request bodies carry the identity of the business object (`referenceId`,
`accountNumber`), and the caller's identity is taken from the body, not from a header.

| # | Method | Full path | Request body | Success status | Response body |
|---|---|---|---|---|---|
| 1 | `POST` | `/internal/cbs/accounts` | `CreateLedgerAccountRequest` | **201 Created** | `BalanceResponse` |
| 2 | `GET` | `/internal/cbs/accounts/{accountNumber}/balance` | – | 200 OK | `BalanceResponse` |
| 3 | `GET` | `/internal/cbs/balances?accountNumbers=...` | – | 200 OK | `List<BalanceResponse>` |
| 4 | `POST` | `/internal/cbs/transfers` | `PostTransferRequest` | 200 OK | `PostingResponse` |
| 5 | `POST` | `/internal/cbs/deposits` | `CashRequest` | 200 OK | `PostingResponse` |
| 6 | `POST` | `/internal/cbs/withdrawals` | `CashRequest` | 200 OK | `PostingResponse` |

### POST /internal/cbs/accounts

Creates a ledger account. The account number is trimmed. If the account already exists you get
`AccountAlreadyExistsException` -> **409 Conflict** with message `"Ledger account already exists: " + accountNumber`.

| Field | Type | Constraints (message text is exact) |
|---|---|---|
| `accountNumber` | `String` | `@NotBlank(message = "accountNumber is required")`, `@Size(max = 50, message = "accountNumber must be at most 50 characters")` |
| `currency` | `String` | `@NotBlank(message = "currency is required")`, `@Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter code such as USD")` |
| `openingBalance` | `BigDecimal` | `@DecimalMin(value = "0.00", message = "openingBalance cannot be negative")`, `@Digits(integer = 15, fraction = 2, message = "openingBalance allows at most 2 decimal places")` — optional (null means `0.00`) |

Response `BalanceResponse` = `record BalanceResponse(String accountNumber, BigDecimal balance, String currency)`.

A non-zero opening balance additionally writes a `DEPOSIT` transaction with
`referenceId = "OPEN-" + accountNumber`, `fromAccountNumber = null`, `toAccountNumber = <number>`,
`fee = 0.00`, `status = "POSTED"` and one `CREDIT` `ledger_entries` row.

### GET /internal/cbs/accounts/{accountNumber}/balance

Path variable `accountNumber` (trimmed). Unknown account -> `AccountNotFoundException` ->
**404 Not Found**, message `"Account not found: " + accountNumber`.

| Response field | Type | Meaning |
|---|---|---|
| `accountNumber` | `String` | the ledger account number |
| `balance` | `BigDecimal` | the current balance |
| `currency` | `String` | the 3-letter currency code |

### GET /internal/cbs/balances

Query parameter `accountNumbers` — a `List<String>`, sent by the callers as a comma-joined string
(`?accountNumbers=A,B`). A `null`/empty list returns `[]` (200). Unknown numbers are simply **absent**
from the result (no error). Read-only transaction.

| Response field | Type | Meaning |
|---|---|---|
| `accountNumber` | `String` | the ledger account number |
| `balance` | `BigDecimal` | the current balance |
| `currency` | `String` | the 3-letter currency code |

### POST /internal/cbs/transfers

Posts a ledger transfer.

| Field | Type | Constraints (exact messages) |
|---|---|---|
| `referenceId` | `String` | `@NotBlank(message = "referenceId is required")`, `@Size(max = 100, message = "referenceId must be at most 100 characters")` |
| `fromAccountNumber` | `String` | `@NotBlank(message = "fromAccountNumber is required")`, `@Size(max = 50, message = "fromAccountNumber must be at most 50 characters")` |
| `toAccountNumber` | `String` | `@NotBlank(message = "toAccountNumber is required")`, `@Size(max = 50, message = "toAccountNumber must be at most 50 characters")` |
| `amount` | `BigDecimal` | `@NotNull(message = "amount is required")`, `@DecimalMin(value = "0.01", message = "amount must be at least 0.01")`, `@Digits(integer = 15, fraction = 2, message = "amount allows at most 2 decimal places")` |
| `fee` | `BigDecimal` | `@DecimalMin(value = "0.00", message = "fee cannot be negative")`, `@Digits(integer = 15, fraction = 2, message = "fee allows at most 2 decimal places")` — optional (null means `0.00`) |

Response `PostingResponse` = `record PostingResponse(String transactionId, String referenceId, String status, BigDecimal amount, BigDecimal fee, LocalDateTime createdAt)`.
`status` is the literal string `"POSTED"`. `transactionId` is the `UUID` of the `ledger_transactions` row.

### POST /internal/cbs/deposits and POST /internal/cbs/withdrawals

Body `CashRequest` for both.

| Field | Type | Constraints (exact messages) |
|---|---|---|
| `referenceId` | `String` | `@NotBlank(message = "referenceId is required")`, `@Size(max = 100, message = "referenceId must be at most 100 characters")` |
| `accountNumber` | `String` | `@NotBlank(message = "accountNumber is required")`, `@Size(max = 50, message = "accountNumber must be at most 50 characters")` |
| `amount` | `BigDecimal` | `@NotNull(message = "amount is required")`, `@DecimalMin(value = "0.01", message = "amount must be at least 0.01")`, `@DecimalMax(value = "1000000.00", message = "amount must be at most 1,000,000.00")`, `@Digits(integer = 15, fraction = 2, message = "amount allows at most 2 decimal places")` |

Response `PostingResponse` (same shape as above).

### Error Response Shape

Every error body is `ErrorResponse` = `record ErrorResponse(LocalDateTime timestamp, int status, String error, String message)`
where `error` is `HttpStatus.getReasonPhrase()`.

| Field | Type |
|---|---|
| `timestamp` | `LocalDateTime` |
| `status` | `int` |
| `error` | `String` (the HTTP reason phrase) |
| `message` | `String` |

```json
{
  "timestamp": "2026-11-02T10:15:31.204",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient balance"
}
```

| Exception | HTTP status | Message |
|---|---|---|
| `AccountNotFoundException` | **404** Not Found | exception message, e.g. `"Account not found: X"` |
| `AccountAlreadyExistsException` | **409** Conflict | e.g. `"Ledger account already exists: X"` |
| `DuplicateReferenceException` | **409** Conflict | `"referenceId was already used with different details"` |
| `InsufficientFundsException` | **422** Unprocessable Entity | `"Insufficient balance"` |
| `InvalidPostingException` | **400** Bad Request | `"Cannot transfer to the same account"`, `"Account is not active"`, `"Currency mismatch between accounts"`, `"Fee account currency does not match"` |
| `DataIntegrityViolationException` | **409** Conflict | `"Duplicate request, please retry"` |
| `MethodArgumentNotValidException` | **400** Bad Request | `"<field>: <constraint message>"` joined by `"; "` |
| `HttpMessageNotReadableException` | **400** Bad Request | `"Malformed request body"` |
| `NoResourceFoundException` | **404** Not Found | `"Not found"` |
| any other `Exception` | **500** Internal Server Error | `"Something went wrong"` (stack trace logged) |

## Database

Database: **`cbs_db`** (`${DB_URL:jdbc:postgresql://localhost:5433/cbs_db}`).
User `${DB_USERNAME:banking_admin}`, password `${DB_PASSWORD:postgre2188}`.
`spring.jpa.hibernate.ddl-auto: update`, `spring.jpa.open-in-view: false`.
Hibernate creates the schema; there is **no** hand-written DDL for `cbs_db` in `postgres/init/`.

### `ledger_accounts` — entity `com.banking.cbs.entity.LedgerAccount`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `account_number` | `character varying(50)` | not null | **PRIMARY KEY** (`ledger_accounts_pkey`) |
| `balance` | `numeric(19,2)` | not null | |
| `created_at` | `timestamp(6) without time zone` | not null | set in `@PrePersist` (`LocalDateTime.now()`), `updatable = false` |
| `currency` | `character varying(3)` | not null | |
| `status` | `character varying(20)` | not null | `CHECK (status IN ('ACTIVE','FROZEN'))` |

`LedgerAccountStatus` enum = `ACTIVE`, `FROZEN`.

### `ledger_entries` — entity `com.banking.cbs.entity.LedgerEntry`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | `bigint` | not null | **PRIMARY KEY**, `generated by default as identity` |
| `account_number` | `character varying(50)` | not null | index `idx_entry_account` |
| `amount` | `numeric(19,2)` | not null | |
| `balance_after` | `numeric(19,2)` | not null | the balance of that account right after the entry |
| `created_at` | `timestamp(6) without time zone` | not null | `@PrePersist`, `updatable = false` |
| `direction` | `character varying(10)` | not null | `CHECK (direction IN ('DEBIT','CREDIT'))` |
| `transaction_id` | `uuid` | not null | index `idx_entry_transaction` |

`EntryDirection` enum = `DEBIT`, `CREDIT`.

### `ledger_transactions` — entity `com.banking.cbs.entity.LedgerTransaction`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | `uuid` | not null | **PRIMARY KEY** (assigned `UUID.randomUUID()`) |
| `amount` | `numeric(19,2)` | not null | |
| `created_at` | `timestamp(6) without time zone` | not null | `@PrePersist`, `updatable = false` |
| `fee` | `numeric(19,2)` | not null | |
| `from_account_number` | `character varying(50)` | **nullable** | null for an opening deposit |
| `reference_id` | `character varying(100)` | not null | **UNIQUE** `uk_ledger_tx_reference` |
| `status` | `character varying(20)` | not null | always the literal `"POSTED"` in current code |
| `to_account_number` | `character varying(50)` | not null | |
| `type` | `character varying(20)` | not null | `TransactionType` = `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `REVERSAL` |

### `command_results` — entity `com.banking.cbs.entity.CommandResult`

Source comment: *"One row for every command CBS has answered, successful or not."*

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `command_id` | `character varying(160)` | not null | **PRIMARY KEY** |
| `created_at` | `timestamp(6) with time zone` | not null | `@PrePersist` (`Instant.now()`), `updatable = false` |
| `outcome` | `character varying(20)` | not null | `"COMPLETED"` or `"FAILED"` |
| `reason_code` | `character varying(50)` | nullable | the rejection code, e.g. `INSUFFICIENT_FUNDS` |

### `outbox_events` — entity `com.banking.cbs.messaging.OutboxEvent`

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `id` | `uuid` | not null | **PRIMARY KEY** (also the `eventId`) |
| `attempts` | `integer` | not null | incremented by `recordFailure` |
| `created_at` | `timestamp(6) with time zone` | not null | `@PrePersist` (`Instant.now()`) |
| `envelope` | `text` | not null | the serialized `EventEnvelope` |
| `event_type` | `character varying(100)` | not null | |
| `last_error` | `character varying(500)` | nullable | truncated to 500 chars |
| `message_key` | `character varying(100)` | not null | Kafka message key (usually the transferId) |
| `published_at` | `timestamp(6) with time zone` | nullable | null = not published yet |
| `topic` | `character varying(100)` | not null | |

Index `idx_outbox_pending (published_at, created_at)`.

### `transit_settlements` — entity `com.banking.cbs.entity.TransitSettlement`

Source comment: *"A transfer's money leaves transit exactly once: either DEPOSITED or REVERSED."*

| Column | Type | Null | Default / constraint |
|---|---|---|---|
| `transfer_id` | `character varying(80)` | not null | **PRIMARY KEY** |
| `created_at` | `timestamp(6) with time zone` | not null | `@PrePersist` (`Instant.now()`) |
| `outcome` | `character varying(20)` | not null | `"DEPOSITED"` or `"REVERSED"` |

Inserted with `insert ... on conflict (transfer_id) do nothing`; returns 1 when the call claimed the
transfer, 0 when it was already settled.

### System accounts

`com.banking.cbs.config.SystemAccountsInitializer` (an `ApplicationRunner`) creates, at every start, any
of the two system accounts that do not exist yet, with `balance = 0.00`, `status = ACTIVE`:

| Account | Config property | Default number | Default currency |
|---|---|---|---|
| Fee income | `app.cbs.fee-account-number` / `app.cbs.fee-account-currency` | `FEE-INCOME` | `USD` |
| Transfer transit | `app.cbs.transit-account-number` / `app.cbs.transit-account-currency` | `TRANSFER-TRANSIT` | `USD` |

`RESERVED_NAMES` in `SagaStepService` = `Set.of("CASH", "TRANSIT", "FEE-INCOME", "TRANSFER-TRANSIT")` —
these names can never be used by a customer account.

Note on the names: the **configured** transit account is `TRANSFER-TRANSIT`
(`app.cbs.transit-account-number`), while the **logical** account name used in the
`ledger_transactions.from/to_account_number` columns is the literal string `"TRANSIT"`
(constant `SagaStepService.TRANSIT = "TRANSIT"`), and the cash side is the literal `"CASH"`
(`SagaStepService.CASH = "CASH"`, `PostingServiceImpl.CASH = "CASH"`). `CASH` and `TRANSIT` are **not**
rows in `ledger_accounts` — they are markers inside the transaction header row only.

## Kafka Topics

`com.banking.cbs.messaging.TopicNames`:

```java
public static final String CBS_COMMANDS = "cbs.commands";
public static final String CBS_EVENTS  = "cbs.events";
public static final String DLT_SUFFIX  = ".DLT";
```

- **Consumes:** `cbs.commands` (group id `core-banking-service`).
- **Publishes:** `cbs.events` (through the outbox).
- DLQ: `cbs.commands.DLT`, `cbs.events.DLT`.

Every topic has 3 partitions, replication factor 1, and a dead-letter twin `<topic>.DLT`.

### Commands consumed on `cbs.commands`

`CbsCommandsConsumer` is a `@KafkaListener(topics = TopicNames.CBS_COMMANDS)` with one method
`onMessage(ConsumerRecord<String, String> record)` that parses the envelope and switches on
`envelope.eventType()`:

| `eventType` | Payload record | Fields | Handler |
|---|---|---|---|
| `Withdraw` | `WithdrawCommand` | `transferId` (String), `commandId` (String), `accountNumber` (String), `amount` (BigDecimal), `fee` (BigDecimal), `destination` (String — `"TRANSIT"` or `"CASH"`) | `SagaStepService.withdraw` |
| `Deposit` | `DepositCommand` | `transferId`, `commandId`, `accountNumber`, `amount` (BigDecimal), `source` (String — `"TRANSIT"` or `"CASH"`) | `SagaStepService.deposit` |
| `Reversal` | `ReversalCommand` | `transferId`, `commandId`, `accountNumber`, `amount` (BigDecimal), `fee` (BigDecimal) | `SagaStepService.reversal` |
| `CreateLedgerAccount` | `CreateLedgerAccountCommand` | `accountNumber` (String), `currency` (String), `openingBalance` (BigDecimal) | `SagaStepService.createLedgerAccount` |
| `CheckBalance` | `CheckBalanceCommand` | `transferId` (String), `commandId` (String), `accountNumber` (String), `amount` (BigDecimal), `fee` (BigDecimal) | `SagaStepService.checkBalance` |

Any other `eventType` is logged at debug level and ignored: `"Ignoring unknown command type {}"`.

### Events published on `cbs.events`

| Event type | Payload record | Fields |
|---|---|---|
| `WithdrawCompleted` | `WithdrawCompletedPayload` | `transferId`, `accountNumber`, `amount` (BigDecimal, `@JsonFormat(STRING)`), `fee` (BigDecimal, `@JsonFormat(STRING)`) |
| `WithdrawFailed` | `FailurePayload` | `transferId`, `reasonCode`, `reason` |
| `DepositCompleted` | `DepositCompletedPayload` | `transferId`, `accountNumber`, `amount` (BigDecimal, `@JsonFormat(STRING)`) |
| `DepositFailed` | `FailurePayload` | `transferId`, `reasonCode`, `reason` |
| `ReversalCompleted` | `ReversalCompletedPayload` | `transferId`, `accountNumber`, `refundedAmount` (BigDecimal, `@JsonFormat(STRING)`) |
| `ReversalFailed` | `FailurePayload` | `transferId`, `reasonCode`, `reason` |
| `LedgerAccountCreated` | `LedgerAccountCreatedPayload` | `accountNumber`, `currency` |
| `LedgerAccountCreationFailed` | `LedgerAccountCreationFailedPayload` | `accountNumber`, `reasonCode`, `reason` |
| `BalanceChecked` | `BalanceCheckedPayload` | `transferId`, `accountNumber`, `availableBalance` (BigDecimal, `@JsonFormat(STRING)`), `requiredAmount` (BigDecimal, `@JsonFormat(STRING)`) |
| `BalanceRejected` | `FailurePayload` | `transferId`, `reasonCode`, `reason` |

Kafka message key = the `transferId` (or the account number for `LedgerAccount*`).
`correlationId` = the transferId; `causationId` = the `eventId` of the command that caused it.

### Message envelope

Every message on Kafka uses the same envelope, produced by `OutboxEventPublisher` with
`eventId = UUID.randomUUID()`, `version = 1` and `producer = app.messaging.producer-name`:

```java
public record EventEnvelope(
        String eventId,
        String eventType,
        int version,
        String correlationId,
        String causationId,
        Instant occurredAt,
        String producer,
        JsonNode payload
) {}
```

### The CheckBalance / BalanceChecked / BalanceRejected flow

Added in `SagaStepService.checkBalance(...)`. Javadoc in the source:

> "Answers whether the account can cover a debit of amount + fee. This is the early check of the saga. It
> runs after the fee is known and before the one-time password is requested, so a user is never sent a code
> for a transaction that cannot succeed. It does not move money: the authoritative check happens when the
> withdrawal is posted, because the balance can change between this check and the posting."

Rules:

1. Validate: `transferId` (max 80), `commandId` **must be exactly `"<transferId>:CHECK_BALANCE"`**,
   `accountNumber` (max 50), `amount` > 0, `fee` >= 0 (missing fee = `0.00`).
   `required = amount + fee`.
2. The check **is not** recorded in `command_results` (it moves no money). Source comment:
   *"The check moves no money, so it is not recorded in command_results. Every resend of the command is
   answered again with the balance of that moment, which is what the user needs to see. A second answer is
   harmless: the saga only accepts it while it still waits for the check."*
3. Account missing -> publish `BalanceRejected` with `reasonCode = "ACCOUNT_NOT_FOUND"`,
   `reason = "Account not found: " + number`.
4. `status != ACTIVE` -> publish `BalanceRejected` with `reasonCode = "ACCOUNT_INACTIVE"`,
   `reason = "Account is not active"`.
5. `available < required` -> publish `BalanceRejected` with `reasonCode = "INSUFFICIENT_FUNDS"`,
   `reason = "Insufficient balance. You have " + available + " available, but this needs " + required`.
6. Otherwise -> publish `BalanceChecked` with
   `BalanceCheckedPayload(transferId, accountNumber, availableBalance = available, requiredAmount = required)`.

Metrics counter `cbs.steps` with tags `step=checkBalance`, `result=completed|rejected`.

## Configuration

`core-banking-service/src/main/resources/application.yaml` — the full `app.*` set:

| Property | Default | Meaning |
|---|---|---|
| `app.cbs.fee-account-number` | `FEE-INCOME` | Ledger account that collects fees |
| `app.cbs.fee-account-currency` | `USD` | Its currency |
| `app.cbs.transit-account-number` | `TRANSFER-TRANSIT` | Ledger account that holds money in flight |
| `app.cbs.transit-account-currency` | `USD` | Its currency |
| `app.messaging.producer-name` | `core-banking-service` | `producer` field of every published envelope |
| `app.messaging.relay-interval-ms` | `1000` | Outbox relay poll interval (`@Scheduled(fixedDelayString = "${app.messaging.relay-interval-ms:1000}")`) |

Bound by `@ConfigurationProperties` records:

- `com.banking.cbs.config.CbsProperties` — `@ConfigurationProperties(prefix = "app.cbs")`, fields
  `feeAccountNumber`, `feeAccountCurrency`, `transitAccountNumber`, `transitAccountCurrency`.
- `com.banking.cbs.messaging.MessagingProperties` — `@ConfigurationProperties(prefix = "app.messaging")`, field `producerName`.

Other (non-`app.*`) settings:

| Property | Value / default | Meaning |
|---|---|---|
| `server.port` | `8084` | HTTP port |
| `management.server.port` | `18084` | private actuator port |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | actuator endpoints |
| `spring.datasource.url` | `${DB_URL:jdbc:postgresql://localhost:5433/cbs_db}` | |
| `spring.datasource.username` | `${DB_USERNAME:banking_admin}` | |
| `spring.datasource.password` | `${DB_PASSWORD:postgre2188}` | |
| `spring.jpa.hibernate.ddl-auto` | `update` | |
| `spring.jpa.open-in-view` | `false` | |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | |
| `spring.kafka.consumer.group-id` | `core-banking-service` | |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | |
| `spring.kafka.consumer.enable-auto-commit` | `false` | |
| `spring.kafka.listener.ack-mode` | `record` | |
| `spring.kafka.listener.concurrency` | `3` | |
| `spring.kafka.producer.acks` | `all` | |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | |
| `spring.kafka.producer.properties.linger.ms` | `0` | |
| `spring.kafka.producer.properties.max.block.ms` | `15000` | |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` | |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` | |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` | |
| `eureka.instance.prefer-ip-address` | `true` | |

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/cbs_db` | JDBC URL of the ledger database |
| `DB_USERNAME` | `banking_admin` | database user |
| `DB_PASSWORD` | `postgre2188` | database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list |
| `EUREKA_URL` | `http://localhost:8761/eureka` | Eureka registry URL |

Also honoured by Spring Boot relaxed binding: `SERVER_PORT`, `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` (these are what `run-all.sh` sets).

#### Environment variables you must set

None are strictly required — every one of them has a working default. Set `DB_PASSWORD` at minimum
if your PostgreSQL uses a different password, and `KAFKA_BOOTSTRAP_SERVERS` / `EUREKA_URL` when the
broker or registry is not on `localhost`.

## Behaviour Rules

### Double-entry posting (REST path) — `PostingServiceImpl`

`postTransfer(PostTransferRequest)`:

1. `referenceId`, `from`, `to` are trimmed; `amount` and `fee` are `setScale(2)`; `fee == null` -> `0.00`.
2. `from.equals(to)` -> `InvalidPostingException("Cannot transfer to the same account")` (**400**).
3. **Idempotency**: `transactionRepository.findByReferenceId(reference)`. If a transaction already exists:
   if `from`, `to`, `amount` and `fee` all match -> the **existing** transaction is returned (no new
   posting, no new entries). Otherwise -> `DuplicateReferenceException("referenceId was already used with
   different details")` (**409**). Source comment: *"the same referenceId must never move money twice."*
4. **Row locking**: the involved accounts are locked with `PESSIMISTIC_WRITE` in a **fixed alphabetical
   order** (`new TreeSet<>(Set.of(from, to))`, plus the fee account when `fee > 0`) — source comment:
   *"Lock every account involved, always in the same order, to avoid deadlocks."*
   The repository query is `select a from LedgerAccount a where a.accountNumber in :numbers order by a.accountNumber`.
5. Missing account -> `AccountNotFoundException("Account not found: " + number)` (**404**).
6. Either account not `ACTIVE` -> `InvalidPostingException("Account is not active")` (**400**).
7. `from.currency != to.currency` -> `InvalidPostingException("Currency mismatch between accounts")` (**400**).
8. Fee account currency mismatch -> `InvalidPostingException("Fee account currency does not match")` (**400**).
9. `from.balance < amount + fee` -> `InsufficientFundsException("Insufficient balance")` (**422**).
10. Apply: `from -= (amount + fee)`, `to += amount`, `feeAccount += fee`.
11. Write `ledger_transactions` row: `type = TRANSFER`, `status = "POSTED"`, `fee`, `amount`.
12. Write `ledger_entries`: one `DEBIT` of `totalDebit = amount + fee` on `from`, one `CREDIT` of `amount`
    on `to`, and — only when `fee > 0` — one `CREDIT` of `fee` on the fee account
    (`app.cbs.fee-account-number`, default `FEE-INCOME`). Each entry stores `balanceAfter`.

`postDeposit` / `postWithdrawal` (`postCash`):

- Idempotency on `referenceId` too. A repeat with a **different** type / account / amount ->
  `DuplicateReferenceException("referenceId was already used with different details")` (**409**);
  an identical repeat -> the previous transaction is returned.
- The single account is locked with `findAllForUpdate(Set.of(number))` — *"Lock the account row so
  concurrent requests cannot overspend."*
- Missing account -> `AccountNotFoundException("Account not found: " + number)` (**404**).
- Not `ACTIVE` -> `InvalidPostingException("Account is not active")` (**400**).
- Deposit: `balance += amount`. Withdrawal: `balance < amount` -> `InsufficientFundsException("Insufficient balance")`
  (**422**), else `balance -= amount`.
- The header row always records `fee = 0.00`, `status = "POSTED"`, and the cash side:
  deposit -> `from = "CASH"`, `to = <account>`; withdrawal -> `from = <account>`, `to = "CASH"`.
- Exactly **one** `ledger_entries` row: `CREDIT` for a deposit, `DEBIT` for a withdrawal, of the amount.

### Double-entry posting (saga path) — `SagaStepService`

All saga steps are `@Transactional` and **all checks happen before the ledger is touched** — source
comment: *"All checks passed. Only now does the ledger change."* A rejection therefore leaves no trace
in the ledger (only a `command_results` row with `outcome = FAILED`).

**Withdraw** (`withdraw` / `applyWithdraw`):

- `destination` must be `"TRANSIT"` or `"CASH"`; a withdrawal to `CASH` may **not** carry a fee
  (`InvalidEventException("A withdrawal to CASH cannot carry a fee")`).
- `commandId` must be exactly `"<transferId>:WITHDRAW"`.
- Idempotency: `results.existsById(commandId)` -> the step is skipped (metric `cbs.steps{step=withdraw,result=duplicate}`).
- Locked accounts: the user's account + the transit account (when `destination = TRANSIT`) + the fee
  account (when `fee > 0`), in alphabetical order.
- Entries: `DEBIT` of `amount + fee` on the user's account; `CREDIT` of `amount` on the transit account
  (when to transit); `CREDIT` of `fee` on the fee account (when `fee > 0`).
- Header row: `referenceId = commandId`, `type = WITHDRAWAL`, `from = <account>`,
  `to = "TRANSIT"` or `"CASH"`, `status = "POSTED"`.

**Deposit** (`deposit` / `applyDeposit`):

- `source` must be `"TRANSIT"` or `"CASH"`; `commandId` must be `"<transferId>:DEPOSIT"`.
- When `source = TRANSIT`: a completed withdrawal for this transfer must exist
  (`ledger_transactions.reference_id = "<transferId>:WITHDRAW"`, `type = WITHDRAWAL`, `to = "TRANSIT"`),
  otherwise `NO_WITHDRAWAL`. Its amount must equal the deposit amount, otherwise `AMOUNT_MISMATCH`.
- The transit account must hold at least the amount, otherwise `TRANSIT_SHORTFALL`.
- The transfer is claimed **last**, after every check: `settlements.insertIfAbsent(transferId, "DEPOSITED")`
  returning 0 -> `ALREADY_SETTLED`. Source comment: *"Claim the transfer last, after every check, so a
  rejection leaves no trace."*
- Entries: `CREDIT` of the amount on the user's account; `DEBIT` of the amount on the transit account
  (when from transit).
- Header row: `type = DEPOSIT`, `from = "TRANSIT"` or `"CASH"`, `to = <account>`, `fee = 0.00`.

**Reversal** (`reversal` / `applyReversal`):

- `commandId` must be `"<transferId>:REVERSAL"`.
- The original withdrawal must exist (same lookup as above), otherwise `NO_WITHDRAWAL`.
- The reversal must match the original exactly: same account, same `amount`, same `fee`, otherwise
  `MISMATCH`. Source comment: *"The ledger decides what is refunded and to whom, never the message."*
- **The account status is ignored on purpose**: *"A refund must work even when the account was frozen in
  the meantime, so the status is ignored."*
- The transit account and (if `fee > 0`) the fee account must hold enough, otherwise `TRANSIT_SHORTFALL`.
- `settlements.insertIfAbsent(transferId, "REVERSED")` returning 0 -> `ALREADY_SETTLED`.
- Refund = `amount + fee`: `account += (amount + fee)`, `transit -= amount`, `feeAccount -= fee`.
- Entries: `CREDIT` of `amount + fee` on the user's account; `DEBIT` of `amount` on the transit account;
  `DEBIT` of `fee` on the fee account (when `fee > 0`).
- Header row: `type = REVERSAL`, `from = "TRANSIT"`, `to = <account>`.

**CreateLedgerAccount**:

- A reserved name -> publish `LedgerAccountCreationFailed` with `reasonCode = "RESERVED_NAME"`,
  `reason = "This account number is reserved"`.
- The account exists with the **same** currency -> publish `LedgerAccountCreated` again (harmless
  duplicate, metric `result=duplicate`).
- The account exists with a **different** currency -> publish `LedgerAccountCreationFailed` with
  `reasonCode = "CURRENCY_CONFLICT"`, `reason = "The account exists with another currency"`.
- Otherwise create the account (via `LedgerAccountService`) and publish `LedgerAccountCreated`.

### Reason / failure codes used by CBS

| Code | Where | Meaning |
|---|---|---|
| `ACCOUNT_NOT_FOUND` | Withdraw, Deposit, Reversal, CheckBalance | the ledger account does not exist |
| `ACCOUNT_INACTIVE` | Withdraw, Deposit, CheckBalance | the account is not `ACTIVE` (FROZEN) |
| `SYSTEM_ACCOUNT_MISSING` | Withdraw, Deposit, Reversal | a system ledger account is missing or has another currency |
| `INSUFFICIENT_FUNDS` | Withdraw, CheckBalance | the balance does not cover the debit |
| `NO_WITHDRAWAL` | Deposit, Reversal | no completed withdrawal found for the transfer |
| `AMOUNT_MISMATCH` | Deposit | the amount does not match the withdrawal |
| `TRANSIT_SHORTFALL` | Deposit, Reversal | the transit (or fee) account holds too little |
| `ALREADY_SETTLED` | Deposit, Reversal | the transfer was already deposited or reversed |
| `MISMATCH` | Reversal | the reversal does not match the original withdrawal |
| `RESERVED_NAME` | CreateLedgerAccount | the account number is a system name |
| `CURRENCY_CONFLICT` | CreateLedgerAccount | the account exists with another currency |

Exception messages (in `FailurePayload.reason`):

- `"Account not found: " + number`
- `"Account is not active"`
- `"A system ledger account is missing"`
- `"System account currency does not match"`
- `"The transit account is missing or has another currency"`
- `"Insufficient balance"`
- `"Insufficient balance. You have " + available + " available, but this needs " + required` (CheckBalance)
- `"No completed withdrawal found for transfer " + transferId`
- `"Amount does not match the withdrawal"`
- `"The transit account holds less than this amount"`
- `"This transfer was already deposited or reversed"`
- `"The reversal does not match the original withdrawal"`
- `"A system account holds less than the refund needs"`
- `"This account number is reserved"`
- `"The account exists with another currency"`

### Command validation (`com.banking.cbs.messaging.CommandValidation`)

- `text(value, field, maxLength)`: null / blank / longer than `maxLength` -> `InvalidEventException(field + " is missing or too long")`.
- `positiveAmount`: null -> `"<field> is missing"`; scale > 2 -> `"<field> allows at most 2 decimal places"`;
  integer digits > 15 -> `"<field> is too large"`; `<= 0` -> `"<field> must be greater than 0"`. Result is `setScale(2)`.
- `nonNegativeAmount`: **null counts as `0.00`**; negative -> `"<field> cannot be negative"`.
- `oneOf(value, field, allowed...)`: not in the list -> `"<field> must be one of " + String.join(", ", allowed)`.
- `commandId(value, transferId, step)`: must be exactly `"<transferId>:<STEP>"`, else
  `"commandId must be " + expected`. Steps used: `WITHDRAW`, `DEPOSIT`, `REVERSAL`, `CHECK_BALANCE`.
- `currency(value)`: must match `[A-Z]{3}`, else `"currency must be 3 capital letters"`.

### Idempotency

Two layers:

1. **`command_results`** — the primary key is the `commandId`. `SagaStepService` checks
   `results.existsById(commandId)` before doing anything; a repeated command is silently ignored
   (metric `result=duplicate`). On completion a row `CommandResult.completed(commandId)` is written
   (`outcome = "COMPLETED"`); on rejection `CommandResult.failed(commandId, reasonCode)`
   (`outcome = "FAILED"`, `reason_code = <code>`).
   `CheckBalance` is the deliberate exception — it is not recorded, because it moves no money.
2. **`ledger_transactions.reference_id`** — unique constraint `uk_ledger_tx_reference`. The REST path
   uses it (`findByReferenceId`); the saga path uses the `commandId` as the `referenceId` of the
   transaction, so `referenceId = "<transferId>:WITHDRAW"` / `":DEPOSIT"` / `":REVERSAL"`.
   A `DataIntegrityViolationException` (two racing identical requests) is turned into **409**
   `"Duplicate request, please retry"` by the REST error handler.

### Outbox and retries

- `EventPublisher.publish(...)` -> `OutboxEventPublisher.publish(...)` is annotated
  `@Transactional(propagation = Propagation.MANDATORY)`: **the event must be written inside the
  transaction that changes the data**, so the change and the event are saved together or not at all.
- `OutboxRelay.publishPending()` — `@Scheduled(fixedDelayString = "${app.messaging.relay-interval-ms:1000}")`,
  takes the oldest **50** unpublished rows (`findTop50ByPublishedAtIsNullOrderByCreatedAtAsc`, locked
  `PESSIMISTIC_WRITE`), publishes each with `kafka.send(...).get(30, TimeUnit.SECONDS)`
  (`PUBLISH_TIMEOUT_SECONDS = 30`). The 30 s wait is deliberately longer than
  `max.block.ms + delivery.timeout.ms` — source comment: *"Otherwise a publish can be reported as failed
  while the message is still on its way, and the retry sends it a second time."*
- On a failure the error is truncated to 500 chars, `attempts` is incremented, and **the loop returns
  immediately** so a later event never overtakes the failed one.
- `deleteOldPublishedEvents()` — `@Scheduled(cron = "0 15 3 * * *")`, deletes published events older
  than **7 days**.
- Consumer error handling (`KafkaConsumerConfig`): `DefaultErrorHandler` with
  `ExponentialBackOffWithMaxRetries(4)`, `initialInterval = 1000 ms`, `multiplier = 2.0`,
  `maxInterval = 10000 ms`; `InvalidEventException` is **not retryable** (goes straight to the DLT);
  the DLT topic is `<topic>.DLT` on the same partition.
- Producer settings: `acks: all`, `enable.idempotence: true`, `linger.ms: 0`, `max.block.ms: 15000`,
  `request.timeout.ms: 5000`, `delivery.timeout.ms: 10000`.

### Metrics

- `cbs.steps` counter, tags `step` (`withdraw|deposit|reversal|create|checkBalance`) and `result`
  (`completed|failed|duplicate|rejected`).
- `cbs.transit.balance` gauge — "Money currently in flight between a withdrawal and its deposit or reversal".
- `cbs.fee.income.balance` gauge — "Fees collected so far".
- `outbox.events.published`, `outbox.publish.failures`, `outbox.pending.events`.

## Running It

Prerequisites:

- Java 17, Maven 3.9+ (the repo also ships `./mvnw`).
- PostgreSQL on `localhost:5433` with the database `cbs_db` (created by
  `postgres/init/01-create-databases.sql`; there is no CBS-specific init script).
- Kafka on `localhost:9092`. The topics must exist — the `event-bus-service` (port 8090) creates them.
- Eureka on `localhost:8761`.

Build and run:

```bash
cd core-banking-service
mvn -q clean package -DskipTests        # -> target/core-banking-service-0.0.1-SNAPSHOT.jar
mvn spring-boot:run
```

Or from the jar:

```bash
java -jar target/core-banking-service-0.0.1-SNAPSHOT.jar
```

The monorepo script does it for you: `./run-all.sh --build` (or `./run-all.sh` to reuse an existing
jar). It sets `SERVER_PORT=8084`,
`SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/cbs_db`,
`SPRING_DATASOURCE_USERNAME=banking_admin`, `SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD`.

Environment:

```bash
export DB_URL='jdbc:postgresql://localhost:5433/cbs_db'
export DB_USERNAME='banking_admin'
export DB_PASSWORD='postgre2188'
export KAFKA_BOOTSTRAP_SERVERS='localhost:9092'
export EUREKA_URL='http://localhost:8761/eureka'
```

Check that it is healthy:

```bash
curl -s http://localhost:18084/actuator/health
curl -s http://localhost:18084/actuator/prometheus | grep -E 'cbs_|outbox_'
```

The actuator is **not** exposed on the public port 8084.

Stop it with `Ctrl-C`, or `pkill -f core-banking-service`.

## API Quick Reference (curl examples)

The `/internal/cbs/*` endpoints are unauthenticated — the gateway blocks `/internal/**`, so call them
directly on port 8084.

```bash
# 1. Create a ledger account (201 Created)
curl -s -i -X POST http://localhost:8084/internal/cbs/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ACC-1001","currency":"USD","openingBalance":100.00}'

# 2. Read one balance (200 OK)
curl -s http://localhost:8084/internal/cbs/accounts/ACC-1001/balance

# 3. Read several balances at once (comma-joined query parameter)
curl -s 'http://localhost:8084/internal/cbs/balances?accountNumbers=ACC-1001,ACC-1002'

# 4. Post a ledger transfer (200 OK)
curl -s -X POST http://localhost:8084/internal/cbs/transfers \
  -H 'Content-Type: application/json' \
  -d '{"referenceId":"TXN-0001","fromAccountNumber":"ACC-1001","toAccountNumber":"ACC-1002","amount":25.00,"fee":1.00}'

# 5. Cash deposit (200 OK)
curl -s -X POST http://localhost:8084/internal/cbs/deposits \
  -H 'Content-Type: application/json' \
  -d '{"referenceId":"CASH-IN-0001","accountNumber":"ACC-1001","amount":50.00}'

# 6. Cash withdrawal (200 OK)
curl -s -X POST http://localhost:8084/internal/cbs/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"referenceId":"CASH-OUT-0001","accountNumber":"ACC-1001","amount":10.00}'

# Repeating a posting with the same referenceId is safe: the same transaction comes back.
curl -s -X POST http://localhost:8084/internal/cbs/deposits \
  -H 'Content-Type: application/json' \
  -d '{"referenceId":"CASH-IN-0001","accountNumber":"ACC-1001","amount":50.00}'
```

## Configuration Reference (quick table)

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8084` | HTTP port |
| `management.server.port` | `18084` | actuator port |
| `app.cbs.fee-account-number` | `FEE-INCOME` | account that collects fees |
| `app.cbs.fee-account-currency` | `USD` | currency of the fee account |
| `app.cbs.transit-account-number` | `TRANSFER-TRANSIT` | account that holds money in flight |
| `app.cbs.transit-account-currency` | `USD` | currency of the transit account |
| `app.messaging.producer-name` | `core-banking-service` | `producer` field of every envelope |
| `app.messaging.relay-interval-ms` | `1000` | outbox relay poll interval |
| `spring.datasource.url` | `${DB_URL:jdbc:postgresql://localhost:5433/cbs_db}` | database URL |
| `spring.datasource.username` | `${DB_USERNAME:banking_admin}` | database user |
| `spring.datasource.password` | `${DB_PASSWORD:postgre2188}` | database password |
| `spring.jpa.hibernate.ddl-auto` | `update` | schema is created/updated by Hibernate |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | broker list |
| `spring.kafka.consumer.group-id` | `core-banking-service` | consumer group |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | start from the beginning on a new group |
| `spring.kafka.listener.concurrency` | `3` | consumer threads |
| `spring.kafka.producer.acks` | `all` | full durability |
| `spring.kafka.producer.properties.max.block.ms` | `15000` | producer block timeout |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` | producer request timeout |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` | producer delivery timeout |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` | Eureka registry |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `404 "Not found"` for `/internal/cbs/...` when calling through the API gateway | The gateway blocks `/internal/**` on purpose | Call port 8084 directly, over the internal network |
| `409 Conflict`, `"Ledger account already exists: ACC-1001"` | The ledger account is already there | Use a different `accountNumber`; the ledger never overwrites an account |
| `409 Conflict`, `"referenceId was already used with different details"` | The same `referenceId` was used with other amounts or accounts | Use a fresh `referenceId` — the same `referenceId` may never move money twice |
| `409 Conflict`, `"Duplicate request, please retry"` | Two identical requests raced each other and the unique key stopped the second | Safe to retry with the same `referenceId` |
| `422 Unprocessable Entity`, `"Insufficient balance"` | The account does not cover `amount + fee` | Top the account up, or lower the amount |
| `400 Bad Request`, `"Account is not active"` | The ledger account is `FROZEN` | Unfreeze it in `ledger_accounts.status` (`ACTIVE` / `FROZEN`) |
| `400 Bad Request`, `"Currency mismatch between accounts"` | The two accounts have different currencies | Both accounts must share a currency |
| `400 Bad Request`, `"Fee account currency does not match"` | `app.cbs.fee-account-currency` differs from the account currency | Set `app.cbs.fee-account-currency` to the same currency |
| `404 Not Found`, `"Account not found: X"` | No row in `ledger_accounts` for that number | Create it with `POST /internal/cbs/accounts` |
| `400 Bad Request`, `"amount: amount allows at most 2 decimal places"` | Bean validation on the request body | Send at most 2 decimal places |
| `400 Bad Request`, `"Malformed request body"` | The JSON does not parse | Check the JSON body |
| `500`, `"Something went wrong"` | An unhandled exception; the stack trace is in the service log | Read the log of the service on port 8084 |
| `WithdrawFailed` / `DepositFailed` with `reasonCode = ALREADY_SETTLED` | The transfer was already deposited or reversed | Expected for a duplicated deposit/reversal command |
| `WithdrawFailed` with `reasonCode = SYSTEM_ACCOUNT_MISSING` | `TRANSFER-TRANSIT` or `FEE-INCOME` was renamed or has another currency | Check `app.cbs.transit-account-number` / `app.cbs.fee-account-number` |
| `ReversalFailed` with `reasonCode = TRANSIT_SHORTFALL` | The transit or fee account holds less than the refund needs | Inspect `TRANSFER-TRANSIT` and `FEE-INCOME` balances |
| A command never gets an answer | The message was parked on `cbs.commands.DLT` | Read the DLT; `InvalidEventException` messages are not retried |
| The outbox never drains (`outbox.pending.events` grows) | Kafka is down, or the broker list is wrong | Check `KAFKA_BOOTSTRAP_SERVERS` and the broker |

## Notes and Known Limitations

- **The service has no authentication of its own.** `/internal/cbs/*` is open to anything that can
  reach port 8084. The only protection is the API gateway's refusal to route `/internal/**`. Do not
  expose port 8084 outside the internal network.
- **`application.yaml` contains a duplicated, dead `spring.kafka:` block** nested under `spring.jpa`
  (lines 36-52 of the file) with `max.block.ms: 5000`. That nested block is not bound to the
  `KafkaProperties` prefix and has no effect; the effective producer timeout is the top-level
  `max.block.ms: 15000`.
- **There is no hand-written DDL for `cbs_db`.** Hibernate creates the schema with
  `ddl-auto: update`. A fresh database therefore starts with the tables missing their
  `CHECK` constraints until Hibernate has run once.
- **`CASH` and `TRANSIT` are not rows.** They are markers in the `from_account_number` /
  `to_account_number` columns of `ledger_transactions`. Only `FEE-INCOME` and `TRANSFER-TRANSIT` exist
  as system rows in `ledger_accounts`.
- **The transit account holds real money.** Between the sender's debit and the receiver's credit (or
  the reversal) the money sits in `TRANSFER-TRANSIT`. The `cbs.transit.balance` gauge shows how much is
  in flight; a non-zero value that never falls means a transfer is stuck.
- **`ledger_transactions.status` is always `"POSTED"`.** There is no `PENDING` or `FAILED` state for a
  transaction: a posting either happens completely or not at all.
- **`CheckBalance` is not idempotent by design** — it is the one command that is not recorded in
  `command_results`, because it moves no money. Every resend is answered again with the balance of that
  moment.
- **The reversal ignores the account status on purpose**, so a refund works even when the account was
  frozen in the meantime.
- **`command_results` grows forever.** There is no cleanup job for it in this service.
- **Omitting the `accountNumbers` query parameter is not handled.** `GET /internal/cbs/balances` declares
  `@RequestParam List<String> accountNumbers` as required and there is no handler for
  `MissingServletRequestParameterException`, so the request falls through to the catch-all and is
  answered with **500** `"Something went wrong"`. Always send the parameter, even if it is empty
  (`?accountNumbers=`).
- **The `LedgerAccountCreated` event is published twice when an account is created twice** with the
  same currency. The duplicate is harmless (metric `cbs.steps{step=create,result=duplicate}`).

# Core Banking Service

**Part of the Banking Microservices Project**

Developed and maintained by **Jahid Hasan**

- **Author:** Jahid Hasan
- **GitHub:** [Md-Jahid-Hasan-10](https://github.com/Md-Jahid-Hasan-10)
- **Repository:** [core-banking-service](https://github.com/Md-Jahid-Hasan-10/core-banking-service)
- **Project:** Banking Microservices
- **Application Port:** `8084`
- **Management/Metrics Port:** `18084`

> The authoritative double-entry ledger of the bank. It owns the ledger tables and is the only component allowed to change account balances.
