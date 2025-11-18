package uk.gov.hmcts.cp.filter.audit.service;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.model.Metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.jms.client.ActiveMQTopic;
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
    void logsAndSendsMessageWhenSerializationSucceeds_toTopic() throws JsonProcessingException, JMSException {
        // given
        final AuditPayload payload = mock(AuditPayload.class);
        final String json = "{\"key\":\"value\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(json);
        when(payload.timestamp()).thenReturn("2024-10-10T10:00:00Z");

        final String auditMethodName = "dummy-name";
        when(payload._metadata()).thenReturn(Metadata.builder().id(randomUUID()).name(auditMethodName).build());

        // when
        auditService.postMessageToArtemis(payload);

        // then: capture Destination + MPP
        ArgumentCaptor<Destination> destCap = ArgumentCaptor.forClass(Destination.class);
        ArgumentCaptor<MessagePostProcessor> mppCap = ArgumentCaptor.forClass(MessagePostProcessor.class);

        verify(jmsTemplate).convertAndSend(destCap.capture(), eq(json), mppCap.capture());

        // destination is a Topic named "jms.topic.auditing.event"
        Destination dest = destCap.getValue();
        assertThat(dest).isInstanceOf(Topic.class);
        assertThat(((Topic) dest).getTopicName()).isEqualTo("jms.topic.auditing.event");

        // optional: also assert it’s the concrete Artemis topic type
        assertThat(dest).isInstanceOf(ActiveMQTopic.class);

        // MPP sets CPPNAME header
        MessagePostProcessor mpp = mppCap.getValue();
        Message message = mock(Message.class);
        mpp.postProcessMessage(message);
        verify(message).setStringProperty(eq("CPPNAME"), eq(auditMethodName));

        // and we serialized exactly once
        verify(objectMapper).writeValueAsString(payload);
        verifyNoMoreInteractions(objectMapper, jmsTemplate);
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
