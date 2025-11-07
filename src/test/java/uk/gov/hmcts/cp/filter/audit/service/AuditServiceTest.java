package uk.gov.hmcts.cp.filter.audit.service;

import static java.util.UUID.randomUUID;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.model.Metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;

class AuditServiceTest {

    private JmsTemplate jmsTemplate;
    private ObjectMapper objectMapper;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        jmsTemplate = mock(JmsTemplate.class);
        objectMapper = mock(ObjectMapper.class);
        auditService = new AuditService(jmsTemplate, objectMapper);
    }

    @Test
    void dontPostMessageToArtemisWhenAuditPayloadIsNull() {

        auditService.postMessageToArtemis(null);

        verifyNoInteractions(objectMapper, jmsTemplate);
    }

    @Test
    void logsAndSendsMessageWhenSerializationSucceeds() throws JsonProcessingException, JMSException {
        final AuditPayload auditPayload = mock(AuditPayload.class);
        final String serializedMessage = "{\"key\":\"value\"}";
        when(objectMapper.writeValueAsString(auditPayload)).thenReturn(serializedMessage);
        when(auditPayload.timestamp()).thenReturn("2024-10-10T10:00:00Z");
        final String auditMethodName = "dummy-name";
        when(auditPayload._metadata()).thenReturn(Metadata.builder().id(randomUUID()).name(auditMethodName).build());

        auditService.postMessageToArtemis(auditPayload);
        // Inside your test method
        final ArgumentCaptor<MessagePostProcessor> captor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(jmsTemplate).convertAndSend(eq("jms.topic.auditing.event"), eq(serializedMessage), captor.capture());

        final Message mockMessage = mock(Message.class);
        captor.getValue().postProcessMessage(mockMessage);
        verify(mockMessage).setStringProperty(eq("CPPNAME"), eq(auditMethodName));

        verify(objectMapper).writeValueAsString(auditPayload);
    }

    @Test
    void logsErrorWhenSerializationFails() throws JsonProcessingException {
        final AuditPayload auditPayload = mock(AuditPayload.class);
        when(objectMapper.writeValueAsString(auditPayload)).thenThrow(new JsonProcessingException("Serialization error") {
        });

        auditService.postMessageToArtemis(auditPayload);

        verify(objectMapper).writeValueAsString(auditPayload);
        verify(jmsTemplate, never()).convertAndSend(anyString(), anyString());
    }
}
