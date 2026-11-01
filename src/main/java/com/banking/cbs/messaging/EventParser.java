package com.banking.cbs.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class EventParser {

    private final ObjectMapper objectMapper;

    public EventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventEnvelope parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("Empty message");
        }
        try {
            EventEnvelope envelope = objectMapper.readValue(value, EventEnvelope.class);
            if (isBlank(envelope.eventId()) || isBlank(envelope.eventType())) {
                throw new InvalidEventException("Message has no eventId or eventType");
            }
            return envelope;
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Message is not valid JSON", ex);
        }
    }

    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        if (envelope.payload() == null || envelope.payload().isNull()) {
            throw new InvalidEventException("Message has no payload");
        }
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Payload does not match " + envelope.eventType(), ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}