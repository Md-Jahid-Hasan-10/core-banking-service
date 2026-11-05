package com.banking.cbs.observability;   // change the package for each service

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Adds the Kafka topic, the message key and (when the key is a transfer id) the transferId to every log line
 * written while a message is being handled. Spring Boot wires this bean into the listener container by itself.
 * It is declared with Object types because that is what Spring Boot's auto-configuration expects.
 */
@Component
public class KafkaLogContextInterceptor implements RecordInterceptor<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(KafkaLogContextInterceptor.class);
    private static final int MAX_KEY_LENGTH = 100;

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        MDC.put("kafkaTopic", record.topic());

        if (record.key() != null) {
            String key = String.valueOf(record.key());
            if (key.length() > MAX_KEY_LENGTH) {
                key = key.substring(0, MAX_KEY_LENGTH);
            }
            MDC.put("messageKey", key);
            if (key.startsWith("TXN-")) {
                MDC.put("transferId", key);
            }
        }

        log.info("Received message topic={} partition={} offset={} key={}",
                record.topic(), record.partition(), record.offset(), record.key());
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove("kafkaTopic");
        MDC.remove("messageKey");
        MDC.remove("transferId");
    }
}