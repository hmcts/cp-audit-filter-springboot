package uk.gov.hmcts.cp.filter.audit.config;

import uk.gov.hmcts.cp.filter.audit.AuditFilter;
import uk.gov.hmcts.cp.filter.audit.JacksonConfig;
import uk.gov.hmcts.cp.filter.audit.parser.OpenApiParserProducer;
import uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser;
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.filter.audit.service.AuditService;
import uk.gov.hmcts.cp.filter.audit.service.OpenApiSpecPathParameterService;
import uk.gov.hmcts.cp.filter.audit.service.PathParameterService;
import uk.gov.hmcts.cp.filter.audit.util.ClasspathResourceLoader;
import uk.gov.hmcts.cp.filter.audit.util.PathParameterNameExtractor;
import uk.gov.hmcts.cp.filter.audit.util.PathParameterValueExtractor;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.parser.OpenAPIParser;
import jakarta.jms.DeliveryMode;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jms.core.JmsTemplate;

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({AuditProperties.class, HttpAuditProperties.class})
public class ArtemisAuditAutoConfiguration {

    @Bean(name = "auditConnectionFactory")
    @ConditionalOnMissingBean(name = "auditConnectionFactory")
    public ActiveMQConnectionFactory auditConnectionFactory(final AuditProperties props) {
        validateProps(props);

        final String url = buildHaConnectionUrl(props);
        logSafeUrl("Configuring Artemis HA connection: {}", url);

        final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
        factory.setUser(Objects.toString(props.getUser(), ""));
        factory.setPassword(Objects.toString(props.getPassword(), ""));
        return factory;
    }

    @Bean(name = "auditJmsTemplate")
    @ConditionalOnMissingBean(name = "auditJmsTemplate")
    public JmsTemplate auditJmsTemplate(final ActiveMQConnectionFactory auditConnectionFactory) {
        final JmsTemplate t = new JmsTemplate(auditConnectionFactory);
        t.setPubSubDomain(true);
        t.setDeliveryMode(DeliveryMode.PERSISTENT);
        t.setReceiveTimeout(5_000);
        return t;
    }

    @Bean(name = "auditObjectMapper")
    @ConditionalOnMissingBean(name = "auditObjectMapper")
    public ObjectMapper auditObjectMapper() {
        return JacksonConfig.objectMapper();
    }

    @Bean
    @ConditionalOnMissingBean(AuditService.class)
    public AuditService auditService(final JmsTemplate auditJmsTemplate,
                                     final ObjectMapper auditObjectMapper) {
        return new AuditService(auditJmsTemplate, auditObjectMapper);
    }


    @Bean
    @ConditionalOnMissingBean(ClasspathResourceLoader.class)
    public ClasspathResourceLoader classpathResourceLoader(final ResourceLoader resourceLoader) {
        return new ClasspathResourceLoader(resourceLoader);
    }

    @Bean
    @ConditionalOnMissingBean(OpenApiParserProducer.class)
    public OpenApiParserProducer openApiParserProducer() {
        return new OpenApiParserProducer();
    }

    @Bean
    @ConditionalOnMissingBean(OpenAPIParser.class)
    public OpenAPIParser openAPIParser(final OpenApiParserProducer producer) {
        return producer.openAPIParser();
    }

    @Bean
    @ConditionalOnMissingBean(PathParameterNameExtractor.class)
    public PathParameterNameExtractor pathParameterNameExtractor() {
        return new PathParameterNameExtractor();
    }

    @Bean
    @ConditionalOnMissingBean(PathParameterValueExtractor.class)
    public PathParameterValueExtractor pathParameterValueExtractor() {
        return new PathParameterValueExtractor();
    }

    @Bean
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = "true")
    @ConditionalOnMissingBean(OpenApiSpecificationParser.class)
    public OpenApiSpecificationParser openApiSpecificationParser(final ClasspathResourceLoader loader,
                                                                 final OpenAPIParser openAPIParser,
                                                                 final HttpAuditProperties httpProps) {
        final OpenApiSpecificationParser p =
                new OpenApiSpecificationParser(loader, httpProps.getOpenapiRestSpec(), openAPIParser, true);
        p.init();
        return p;
    }

    @Bean
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = "true")
    @ConditionalOnMissingBean(OpenApiSpecPathParameterService.class)
    public OpenApiSpecPathParameterService pathParameterService(final OpenApiSpecificationParser parser,
                                                                final PathParameterNameExtractor nameExtractor,
                                                                final PathParameterValueExtractor valueExtractor) {
        return new OpenApiSpecPathParameterService(parser, nameExtractor, valueExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(AuditPayloadGenerationService.class)
    public AuditPayloadGenerationService auditPayloadGenerationService(final ObjectMapper auditObjectMapper) {
        return new AuditPayloadGenerationService(auditObjectMapper);
    }


    @Bean
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = "true")
    @ConditionalOnMissingBean(AuditFilter.class)
    public AuditFilter auditFilter(final AuditService auditService,
                                   final AuditPayloadGenerationService generator,
                                   final PathParameterService pathParameterService) {
        return new AuditFilter(auditService, generator, pathParameterService);
    }


    private static void validateProps(final AuditProperties props) {
        final List<String> hosts = props.getHosts();
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalStateException("cp.audit.hosts must contain at least one broker host");
        }
        if (props.getPort() <= 0) {
            throw new IllegalStateException("cp.audit.port must be a positive integer");
        }
        if (props.isSslEnabled()) {
            if (props.getTruststore() == null || props.getTruststore().isBlank()) {
                throw new IllegalStateException("cp.audit.truststore must be set when ssl-enabled=true");
            }
            if (props.getTruststorePassword() == null) {
                throw new IllegalStateException("cp.audit.truststore-password must be set when ssl-enabled=true");
            }
        }
    }

    /**
     * Build a comma-separated Artemis HA URL with all reconnection/timeouts/failover options
     * expressed as URI parameters (preferred over deprecated setters).
     * <p>
     * Example (non-SSL):
     * tcp://a:61616?ha=true&reconnectAttempts=-1&initialConnectAttempts=10&retryInterval=2000&retryIntervalMultiplier=1.5&maxRetryInterval=30000&connectionTtl=60000&callTimeout=15000&failoverOnInitialConnection=true,
     * tcp://b:61616?ha=true&...
     * <p>
     * Example (SSL):
     * tcp://a:61617?sslEnabled=true&trustStorePath=/path/trust.jks&trustStorePassword=*****&ha=true&...
     */
    private String buildHaConnectionUrl(final AuditProperties props) {
        final var jmsProperties = props.getJms();

        final String common =
                new StringJoiner("&")
                        .add("ha=true")
                        .add("reconnectAttempts=" + jmsProperties.getReconnectAttempts())
                        .add("initialConnectAttempts=" + jmsProperties.getInitialConnectAttempts())
                        .add("retryInterval=" + jmsProperties.getRetryIntervalMs())
                        .add("retryIntervalMultiplier=" + jmsProperties.getRetryMultiplier())
                        .add("maxRetryInterval=" + jmsProperties.getMaxRetryIntervalMs())
                        .add("connectionTtl=" + jmsProperties.getConnectionTtlMs())
                        .add("callTimeout=" + jmsProperties.getCallTimeoutMs())
                        .add("failoverOnInitialConnection=true")
                        .toString();

        final StringJoiner joiner = new StringJoiner(",");
        for (String host : props.getHosts()) {
            final StringBuilder node = new StringBuilder("tcp://")
                    .append(host).append(":").append(props.getPort()).append("?");

            if (props.isSslEnabled()) {
                node.append("sslEnabled=true")
                        .append("&trustStorePath=").append(props.getTruststore())
                        .append("&trustStorePassword=").append(props.getTruststorePassword())
                        .append("&");
            }
            node.append(common);
            joiner.add(node.toString());
        }
        return joiner.toString();
    }

    /**
     * Avoid logging secrets (masks trustStorePassword’s value if present).
     */
    private void logSafeUrl(final String pattern, final String url) {
        if (url == null) {
            logSafeUrl("Configuring Artemis HA connection:", url);
            return;
        }
        final String masked = url.replaceAll("(trustStorePassword=)[^&,]+", "$1*****");
        log.info(pattern, masked);
    }
}
