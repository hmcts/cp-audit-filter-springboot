package uk.gov.hmcts.cp.filter.audit.config;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "cp.audit")
public class AuditProperties {

    /** Broker hosts (DNS or IP). */
    private List<String> hosts;

    /** Broker port. */
    private int port;

    /** Optional broker username. */
    private String user;

    /** Optional broker password. */
    private String password;

    /** Enable Artemis HA (failover). */
    private boolean ha = false;

    /** SSL/TLS settings. */
    private boolean sslEnabled = false;
    private boolean verifyHost = false;
    private boolean clientAuthRequired = false;

    /** Keystore used for client auth (and/or as truststore if truststore not set). */
    private String keystore;
    private String keystorePassword;

    /** Truststore explicitly, if provided. */
    private String truststore;
    private String truststorePassword;

    /** JMS tuning. */
    private final JmsProperties jms = new JmsProperties();

    @Getter
    @Setter
    public static class JmsProperties {
        /** Number of reconnection attempts (-1 = infinite). */
        private int reconnectAttempts = -1;

        /** Initial connect attempts before failing fast. */
        private int initialConnectAttempts = 10;

        /** Retry delay between connect attempts (ms). */
        private long retryIntervalMs = 2_000L;

        /** Backoff multiplier for retryInterval. */
        private double retryMultiplier = 1.5d;

        /** Max retry delay (ms). */
        private long maxRetryIntervalMs = 30_000L;

        /** Connection TTL / liveness (ms). */
        private long connectionTtlMs = 60_000L;

        /** Call timeout (ms) for broker round-trips. */
        private long callTimeoutMs = 15_000L;

        /** Client failure check period (ms). */
        private long clientFailureCheckPeriodMs = 3_000L;

        /** Spring CCF session cache size. */
        private int sessionCacheSize = 1;

        /** Cache producers on the Spring CCF. */
        private boolean cacheProducers = false;

        /** Cache consumers on the Spring CCF. */
        private boolean cacheConsumers = false;

        /** JmsTemplate receive timeout (ms). */
        private long receiveTimeoutMs = 5_000L;

        /** Whether to enable explicit QoS on JmsTemplate (delivery mode, priority, TTL). */
        private boolean explicitQosEnabled = true;
    }
}
