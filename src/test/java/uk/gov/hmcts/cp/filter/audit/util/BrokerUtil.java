package uk.gov.hmcts.cp.filter.audit.util;

import static java.util.UUID.randomUUID;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrokerUtil implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrokerUtil.class);

    private static final String TOPIC_NAME = "jms.topic.auditing.event";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

    public BrokerUtil(String url) throws Exception {

        LOGGER.info("Connecting to URL: {}", url);
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(url);
        connection = connectionFactory.createConnection();
        connection.setClientID(randomUUID().toString()); // required for durable subscriptions
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        final String selector = "CPPNAME = 'audit.events.audit-recorded'";
        final Topic topic = session.createTopic(TOPIC_NAME);
        consumer = session.createConsumer(topic, selector);

        consumer.setMessageListener(message -> {
            if (message instanceof TextMessage textMessage) {
                try {
                    final String messageFromTopic = textMessage.getText();
                    receivedMessages.add(messageFromTopic);
                    LOGGER.info("Received message from topic : {}", messageFromTopic);
                } catch (JMSException e) {
                    LOGGER.error("Unable to process message received from topic", e);
                }
            }
        });
    }

    public String getMessageMatching(Predicate<JsonNode> matcher) throws Exception {
        long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < end) {
            String message = receivedMessages.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (message == null) break;
            try {
                JsonNode json = OBJECT_MAPPER.readTree(message);
                if (matcher.test(json)) {
                    return message;
                }
            } catch (Exception e) {
                throw e;
            }
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        consumer.close();
        session.close();
        connection.close();
    }
}