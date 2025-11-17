package uk.gov.hmcts.cp.filter.audit.config;

import uk.gov.hmcts.cp.filter.audit.AuditFilter;
import uk.gov.hmcts.cp.filter.audit.config.AuditProperties.JmsProperties;
import uk.gov.hmcts.cp.filter.audit.parser.OpenApiParserProducer;
import uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser;
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.filter.audit.service.AuditService;
import uk.gov.hmcts.cp.filter.audit.service.OpenApiSpecPathParameterService;
import uk.gov.hmcts.cp.filter.audit.service.PathParameterService;
import uk.gov.hmcts.cp.filter.audit.util.ClasspathResourceLoader;
import uk.gov.hmcts.cp.filter.audit.util.PathParameterNameExtractor;
import uk.gov.hmcts.cp.filter.audit.util.PathParameterValueExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.parser.OpenAPIParser;
import jakarta.jms.DeliveryMode;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jms.core.JmsTemplate;

@Slf4j
@AutoConfiguration
@ConditionalOnClass(ActiveMQConnectionFactory.class)
@ConditionalOnProperty(prefix = "cp.audit", name = "enabled", havingValue = ArtemisAuditAutoConfiguration.TRUE, matchIfMissing = true)
@EnableConfigurationProperties({AuditProperties.class, HttpAuditProperties.class})
public class ArtemisAuditAutoConfiguration {

    public static final String TRUE = "true";

    @Bean(name = "auditConnectionFactory")
    @ConditionalOnMissingBean(name = "auditConnectionFactory")
    public ActiveMQConnectionFactory auditConnectionFactory(final AuditProperties props) {
        validateProps(props);

        final String url = buildHaConnectionUrl(props);
        logSafeUrlSummary(props);

        final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
        factory.setUser(Objects.toString(props.getUser(), ""));
        factory.setPassword(Objects.toString(props.getPassword(), ""));
        return factory;
    }

    @Bean(name = "auditJmsTemplate")
    @ConditionalOnMissingBean(name = "auditJmsTemplate")
    public JmsTemplate auditJmsTemplate(final ActiveMQConnectionFactory auditConnectionFactory) {
        final JmsTemplate jmsTemplate = new JmsTemplate(auditConnectionFactory);
        jmsTemplate.setPubSubDomain(true);
        jmsTemplate.setDeliveryMode(DeliveryMode.PERSISTENT);
        jmsTemplate.setReceiveTimeout(5_000);
        return jmsTemplate;
    }

    @Bean(name = "auditObjectMapper")
    @ConditionalOnMissingBean(name = "auditObjectMapper")
    public ObjectMapper objectMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        return mapper;
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
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = TRUE)
    @ConditionalOnMissingBean(OpenApiSpecificationParser.class)
    public OpenApiSpecificationParser openApiSpecificationParser(final ClasspathResourceLoader loader,
                                                                 final OpenAPIParser openAPIParser,
                                                                 final HttpAuditProperties httpProps) {
        final OpenApiSpecificationParser parser =
                new OpenApiSpecificationParser(loader, httpProps.getOpenapiRestSpec(), openAPIParser, true);
        parser.init();
        return parser;
    }

    @Bean
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = TRUE)
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
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = TRUE)
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

    private String buildHaConnectionUrl(final AuditProperties props) {
        final JmsProperties jms = props.getJms();

        final String common = String.join("&",
                "ha=true",
                "reconnectAttempts=" + jms.getReconnectAttempts(),
                "initialConnectAttempts=" + jms.getInitialConnectAttempts(),
                "retryInterval=" + jms.getRetryIntervalMs(),
                "retryIntervalMultiplier=" + jms.getRetryMultiplier(),
                "maxRetryInterval=" + jms.getMaxRetryIntervalMs(),
                "connectionTtl=" + jms.getConnectionTtlMs(),
                "callTimeout=" + jms.getCallTimeoutMs(),
                "failoverOnInitialConnection=true"
        );

        final int port = props.getPort();
        final boolean ssl = props.isSslEnabled();
        // Precompute SSL prefix once (avoid per-iteration allocations)
        final String sslPrefix = ssl
                ? "sslEnabled=true&trustStorePath=" + props.getTruststore()
                + "&trustStorePassword=" + props.getTruststorePassword() + "&"
                : "";

        final StringBuilder urls = new StringBuilder();
        final java.util.List<String> hosts = props.getHosts();

        for (int itemIndex = 0, n = hosts.size(); itemIndex < n; itemIndex++) {
            final String host = hosts.get(itemIndex);
            urls.append("tcp://").append(host).append(':').append(port).append('?')
                    .append(sslPrefix).append(common);
            if (itemIndex < n - 1) {
                urls.append(',');
            }
        }
        return urls.toString();
    }

    private void logSafeUrlSummary(final AuditProperties props) {
        if (!log.isDebugEnabled()) {
            return;
        }
        final boolean ssl = props.isSslEnabled();
        final String hosts = String.join(",", props.getHosts());
        final int port = props.getPort();
        final JmsProperties jmsProperties = props.getJms();

        log.debug("Configuring Artemis connection: hosts={}, port={}, ssl={}, ha=true, " +
                        "reconnectAttempts={}, initialConnectAttempts={}, retryIntervalMs={}, " +
                        "retryMultiplier={}, maxRetryIntervalMs={}, connectionTtlMs={}, callTimeoutMs={}",
                hosts, port, ssl,
                jmsProperties.getReconnectAttempts(), jmsProperties.getInitialConnectAttempts(), jmsProperties.getRetryIntervalMs(),
                jmsProperties.getRetryMultiplier(), jmsProperties.getMaxRetryIntervalMs(), jmsProperties.getConnectionTtlMs(),
                jmsProperties.getCallTimeoutMs());
    }
}
