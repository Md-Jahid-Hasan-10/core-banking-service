package com.banking.cbs.messaging.consumer;

import com.banking.cbs.messaging.EventEnvelope;
import com.banking.cbs.messaging.EventParser;
import com.banking.cbs.messaging.SagaStepService;
import com.banking.cbs.messaging.TopicNames;
import com.banking.cbs.messaging.payload.CheckBalanceCommand;
import com.banking.cbs.messaging.payload.CreateLedgerAccountCommand;
import com.banking.cbs.messaging.payload.DepositCommand;
import com.banking.cbs.messaging.payload.ReversalCommand;
import com.banking.cbs.messaging.payload.WithdrawCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CbsCommandsConsumer {

    private static final Logger log = LoggerFactory.getLogger(CbsCommandsConsumer.class);

    private final EventParser parser;
    private final SagaStepService steps;

    public CbsCommandsConsumer(EventParser parser, SagaStepService steps) {
        this.parser = parser;
        this.steps = steps;
    }

    @KafkaListener(topics = TopicNames.CBS_COMMANDS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        switch (envelope.eventType()) {
            case "Withdraw" -> steps.withdraw(envelope, parser.payload(envelope, WithdrawCommand.class));
            case "Deposit" -> steps.deposit(envelope, parser.payload(envelope, DepositCommand.class));
            case "Reversal" -> steps.reversal(envelope, parser.payload(envelope, ReversalCommand.class));
            case "CreateLedgerAccount" -> steps.createLedgerAccount(envelope,
                    parser.payload(envelope, CreateLedgerAccountCommand.class));
            case "CheckBalance" -> steps.checkBalance(envelope,
                    parser.payload(envelope, CheckBalanceCommand.class));
            default -> log.debug("Ignoring unknown command type {}", envelope.eventType());
        }
    }
}