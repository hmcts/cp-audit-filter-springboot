package uk.gov.hmcts.cp.filter.audit.config;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "cp.audit")
public class AuditProperties {

    private List<String> hosts;
    private int port;
    private String user;
    private String password;

    private boolean ha = false;

    private boolean sslEnabled = false;
    private boolean verifyHost = false;
    private boolean clientAuthRequired = false;

    private String keystore;
    private String keystorePassword;

    private String truststore;
    private String truststorePassword;

    // JMS tuning
    private final JmsProperties jms = new JmsProperties();

    @Getter
    @Setter
    public static class JmsProperties {
        private int sessionCacheSize = 10;
        private int reconnectAttempts = -1;
        private int initialConnectAttempts = 10;
        private long retryIntervalMs = 2000;
        private double retryMultiplier = 1.5;
        private long maxRetryIntervalMs = 30_000;
        private long connectionTtlMs = 60_000;
        private long callTimeoutMs = 15_000;
    }
}
