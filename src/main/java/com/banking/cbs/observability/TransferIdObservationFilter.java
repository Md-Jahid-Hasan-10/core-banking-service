package com.banking.cbs.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext;
import org.springframework.stereotype.Component;

/**
 * Adds the transfer id as the attribute "transferId" to the span of every Kafka send and receive,
 * so that Tempo can find all traces of one transfer. Spring Boot registers this bean automatically.
 */
@Component
public class TransferIdObservationFilter implements ObservationFilter {

    private static final String PREFIX = "TXN-";
    private static final int MAX_LENGTH = 60;

    @Override
    public Observation.Context map(Observation.Context context) {
        String key = null;
        if (context instanceof KafkaRecordReceiverContext receiver && receiver.getCarrier() != null) {
            key = asText(receiver.getCarrier().key());
        } else if (context instanceof KafkaRecordSenderContext sender && sender.getCarrier() != null) {
            key = asText(sender.getCarrier().key());
        }

        // Only a well formed transfer id is accepted, so nothing else can become a trace attribute.
        if (key != null && key.startsWith(PREFIX) && key.length() <= MAX_LENGTH) {
            context.addHighCardinalityKeyValue(KeyValue.of("transferId", key));
        }
        return context;
    }

    private static String asText(Object key) {
        return key instanceof String text ? text : null;
    }
}