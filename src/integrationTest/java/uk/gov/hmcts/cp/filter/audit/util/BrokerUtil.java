package uk.gov.hmcts.cp.filter.audit.util;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
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

/**
 * Lightweight test helper for consuming audit messages from an Artemis topic.
 * Defaults:
 * - topic:    jms.topic.auditing.event
 * - selector: CPPNAME = 'audit.events.audit-recorded'
 * - wait:     5s
 * <p>
 * Usage:
 * try (BrokerUtil b = BrokerUtil.builder("tcp://localhost:61616").build()) { ... }
 */
public final class BrokerUtil implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerUtil.class);

    public static final String DEFAULT_TOPIC = "jms.topic.auditing.event";
    public static final String DEFAULT_SELECTOR = "CPPNAME = 'audit.events.audit-recorded'";
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(5);
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    // immutable config
    private final String brokerUrl;
    private final String topicName;
    private final Optional<String> selector;
    private final Duration waitForMatch;
    private final Optional<String> username;
    private final Optional<String> password;
    private final boolean durable;
    private final Optional<String> clientId;
    private final Optional<String> durableName;
    private final ObjectMapper mapper;

    // runtime
    private ActiveMQConnectionFactory connectionFactory;
    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

    public static Builder builder(String brokerUrl) {
        return new Builder(brokerUrl);
    }

    public static final class Builder {
        private final String brokerUrl;
        private String topicName = DEFAULT_TOPIC;
        private Optional<String> selector = Optional.of(DEFAULT_SELECTOR);
        private Duration wait = DEFAULT_WAIT;
        private Optional<String> username = Optional.empty();
        private Optional<String> password = Optional.empty();
        private boolean durable = false;
        private Optional<String> clientId = Optional.empty();
        private Optional<String> durableName = Optional.empty();
        private ObjectMapper mapper = DEFAULT_MAPPER;

        private Builder(String brokerUrl) {
            this.brokerUrl = Objects.requireNonNull(brokerUrl, "brokerUrl must not be null");
        }

        public Builder topic(String topic) {
            this.topicName = (topic == null || topic.isBlank()) ? DEFAULT_TOPIC : topic;
            return this;
        }

        public Builder waitFor(Duration timeout) {
            this.wait = (timeout == null) ? DEFAULT_WAIT : timeout;
            return this;
        }


        public BrokerUtil build() throws JMSException {
            if (durable && (clientId.isEmpty() || durableName.isEmpty())) {
                throw new IllegalArgumentException("Durable subscription requires clientId and durableName");
            }
            return new BrokerUtil(this);
        }
    }

    private BrokerUtil(Builder b) throws JMSException {
        this.brokerUrl = b.brokerUrl;
        this.topicName = b.topicName;
        this.selector = b.selector;
        this.waitForMatch = b.wait;
        this.username = b.username;
        this.password = b.password;
        this.durable = b.durable;
        this.clientId = b.clientId;
        this.durableName = b.durableName;
        this.mapper = b.mapper;
        init();
    }

    private void init() throws JMSException {
        LOG.info("Connecting to Artemis at {}", brokerUrl);
        connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
        connection = username.isPresent()
                ? connectionFactory.createConnection(username.get(), password.orElse(null))
                : connectionFactory.createConnection();

        connection.setExceptionListener(ex ->
                LOG.warn("JMS connection exception from {}: {}", brokerUrl, ex.toString(), ex));

        clientId.ifPresent(id -> {
            try {
                connection.setClientID(id);
            } catch (JMSException e) {
                throw new IllegalStateException("Failed to set JMS clientID '" + id + "'", e);
            }
        });

        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        final Topic topic = session.createTopic(topicName);

        if (durable) {
            consumer = selector.isPresent()
                    ? session.createDurableSubscriber(topic, durableName.get(), selector.get(), false)
                    : session.createDurableSubscriber(topic, durableName.get());
            LOG.info("Durable consumer '{}' on topic {} {}", durableName.get(), topicName,
                    selector.map(s -> "with selector: " + s).orElse("(no selector)"));
        } else {
            consumer = selector.isPresent() ? session.createConsumer(topic, selector.get())
                    : session.createConsumer(topic);
            LOG.info("Consumer on topic {} {}", topicName,
                    selector.map(s -> "with selector: " + s).orElse("(no selector)"));
        }

        consumer.setMessageListener(msg -> {
            try {
                if (msg instanceof TextMessage tm) {
                    receivedMessages.add(tm.getText());
                }
            } catch (JMSException e) {
                LOG.error("Unable to process message from topic {}", topicName, e);
            }
        });

        connection.start();
    }

    /**
     * Returns first matching JSON (raw string) within the configured wait; null if none.
     */
    public String getMessageMatching(Predicate<JsonNode> matcher) throws Exception {
        final long end = System.currentTimeMillis() + waitForMatch.toMillis();
        while (System.currentTimeMillis() < end) {
            final String msg = receivedMessages.poll(
                    Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            if (matcher.test(mapper.readTree(msg))) return msg;
        }
        return null;
    }

    @Override
    public void close() {
        closeQuietly(consumer, "JMS consumer");
        closeQuietly(session, "JMS session");
        closeQuietly(connection, "JMS connection");
        closeQuietly(connectionFactory, "JMS connection factory");
    }

    private static void closeQuietly(AutoCloseable autoCloseable, String label) {
        if (autoCloseable == null) return;
        try {
            autoCloseable.close();
        } catch (Exception e) {
            LoggerFactory.getLogger(BrokerUtil.class).warn("Error closing {}", label, e);
        }
    }
}
