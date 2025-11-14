package uk.gov.hmcts.jms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import uk.gov.hmcts.ExampleApplication;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
@SpringBootTest(
        classes = ExampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jms.listener.auto-startup=false"
        }
)
class PostAuditMessageArtemisIntegrationTest {

    private static final int AMQ_PORT = 61616;
    private static final String TOPIC_NAME = "jms.topic.auditing.event";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final GenericContainer<?> artemis =
            new GenericContainer<>("apache/activemq-artemis:2.30.0-alpine")
                    .withEnv("ACTIVEMQ_ENABLED_AUTH", "false")
                    .withEnv("ANONYMOUS_LOGIN", "true")
                    .withExposedPorts(AMQ_PORT)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofSeconds(60)));

    static {
        artemis.start();
    }

    @DynamicPropertySource
    static void registerArtemisProps(DynamicPropertyRegistry registry) {
        registry.add("audit.http.enabled", () -> "true");
        registry.add("audit.http.openapi-rest-spec", () -> "test-openapi.yaml");
        registry.add("spring.artemis.mode", () -> "native");
        registry.add("spring.artemis.broker-url",
                () -> "tcp://" + artemis.getHost() + ":" + artemis.getMappedPort(AMQ_PORT));
        registry.add("spring.jms.pub-sub-domain", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void should_send_audit_message_to_artemis_when_hitting_root_endpoint() throws Exception {
        String brokerUrl = "tcp://" + artemis.getHost() + ":" + artemis.getMappedPort(AMQ_PORT);
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
        
        BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
        Connection connection;
        try {
            connection = connectionFactory.createConnection();
        } catch (jakarta.jms.JMSSecurityException e) {
            try (ActiveMQConnectionFactory authFactory = new ActiveMQConnectionFactory(brokerUrl)) {
                authFactory.setUser("admin");
                authFactory.setPassword("admin");
                connection = authFactory.createConnection("admin", "admin");
            }
        }
        connection.setClientID(randomUUID().toString());
        connection.start();
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(TOPIC_NAME);
        MessageConsumer consumer = session.createConsumer(topic);

        consumer.setMessageListener(message -> {
            if (message instanceof TextMessage textMessage) {
                try {
                    receivedMessages.add(textMessage.getText());
                } catch (JMSException e) {
                    // Ignore
                }
            }
        });

        try {
            Thread.sleep(100);

            var response = restTemplate.getForEntity("/", String.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

            String msg = receivedMessages.poll(5, TimeUnit.SECONDS);
            assertThat(msg).isNotNull().isNotEmpty();

            JsonNode auditJson = OBJECT_MAPPER.readTree(msg);
            assertThat(auditJson.get("content")).isNotNull();
            assertThat(auditJson.get("timestamp")).isNotNull();
            assertThat(auditJson.get("_metadata")).isNotNull();
        } finally {
            consumer.close();
            session.close();
            connection.close();
        }
    }
}