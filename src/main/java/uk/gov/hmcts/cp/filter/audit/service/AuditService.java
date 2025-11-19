package uk.gov.hmcts.cp.filter.audit.service;

import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Destination;
import lombok.RequiredArgsConstructor;
import org.apache.activemq.artemis.jms.client.ActiveMQTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;

@RequiredArgsConstructor
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);

    private final JmsTemplate jmsTemplate;

    private final ObjectMapper objectMapper;

    private final Destination auditTopic = new ActiveMQTopic("jms.topic.auditing.event");

    public void postMessageToArtemis(final AuditPayload auditPayload) {

        if (null == auditPayload) {
            LOGGER.warn("AuditPayload is null");
            return;
        }

        try {
            final String valueAsString = objectMapper.writeValueAsString(auditPayload);
            LOGGER.info("Posting audit message to Artemis with ID = {} and timestamp = {}", auditPayload._metadata().id(), auditPayload.timestamp());
            jmsTemplate.convertAndSend(auditTopic, valueAsString, message -> {
                message.setStringProperty("CPPNAME", auditPayload._metadata().name());
                return message;
            });
            LOGGER.info("Posted audit message to Artemis with ID = {} and timestamp = {}", auditPayload._metadata().id(), auditPayload.timestamp());
        } catch (JsonProcessingException e) {
            // Log the error but don't re-throw to avoid breaking the main request flow
            final UUID auditMetadataId = (auditPayload._metadata() != null) ? auditPayload._metadata().id() : null;
            if (auditMetadataId != null) {
                LOGGER.error("Failed to post audit message with ID {} to Artemis", auditMetadataId);
            } else {
                LOGGER.error("Failed to post audit message to Artemis");
            }
        }

    }
}