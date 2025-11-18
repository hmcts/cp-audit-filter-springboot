package uk.gov.hmcts.cp.filter.audit.config;

import static org.springframework.util.StringUtils.hasLength;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

@Slf4j
@AutoConfiguration
@ConditionalOnClass(ActiveMQConnectionFactory.class)
@ConditionalOnProperty(prefix = "cp.audit", name = "enabled", havingValue = ArtemisAuditAutoConfiguration.TRUE, matchIfMissing = true)
@EnableConfigurationProperties({AuditProperties.class, HttpAuditProperties.class})
public class ArtemisAuditAutoConfiguration {

    public static final String TRUE = "true";
    private static final String BEAN_CF  = "auditConnectionFactory";
    private static final String BEAN_JMS = "auditJmsTemplate";
    private static final String BEAN_OM  = "auditObjectMapper";
    private static final String AUDIT_HTTP_ENABLED = "audit.http.enabled";

    @Bean(name = BEAN_CF)
    @Primary
    @ConditionalOnMissingBean(name = BEAN_CF)
    public ActiveMQConnectionFactory auditConnectionFactory(final AuditProperties properties) {
        validateProps(properties);
        final String url = buildHaConnectionUrl(properties);
        logSafeUrlSummary(properties);

        final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
        factory.setUser(Objects.toString(properties.getUser(), ""));
        factory.setPassword(Objects.toString(properties.getPassword(), ""));
        return factory;
    }

    @Bean(name = BEAN_JMS)
    @Primary
    @ConditionalOnMissingBean(name = BEAN_JMS)
    public JmsTemplate auditJmsTemplate(
            @Qualifier(BEAN_CF) final ActiveMQConnectionFactory connectionFactory,
            final AuditProperties properties
    ) {
        final CachingConnectionFactory caching = new CachingConnectionFactory(connectionFactory);
        final AuditProperties.JmsProperties jmsProps = properties.getJms();
        caching.setSessionCacheSize(jmsProps.getSessionCacheSize());
        caching.setCacheProducers(true);
        caching.setReconnectOnException(true);

        final JmsTemplate jmsTemplate = new JmsTemplate(caching);
        jmsTemplate.setPubSubDomain(true);
        jmsTemplate.setDeliveryMode(DeliveryMode.PERSISTENT);
        jmsTemplate.setReceiveTimeout(5_000L);
        return jmsTemplate;
    }

    @Bean(name = BEAN_OM)
    @ConditionalOnMissingBean(name = BEAN_OM)
    public ObjectMapper auditObjectMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean(AuditService.class)
    public AuditService auditService(
            @Qualifier(BEAN_JMS) final JmsTemplate jmsTemplate,
            @Qualifier(BEAN_OM)  final ObjectMapper objectMapper
    ) {
        return new AuditService(jmsTemplate, objectMapper);
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
    @ConditionalOnProperty(name = AUDIT_HTTP_ENABLED, havingValue = TRUE)
    @ConditionalOnMissingBean(OpenApiSpecificationParser.class)
    public OpenApiSpecificationParser openApiSpecificationParser(
            final ClasspathResourceLoader loader,
            final OpenAPIParser openAPIParser,
            final HttpAuditProperties httpProps
    ) {
        final OpenApiSpecificationParser parser =
                new OpenApiSpecificationParser(loader, httpProps.getOpenapiRestSpec(), openAPIParser, true);
        parser.init();
        return parser;
    }

    @Bean
    @ConditionalOnProperty(name = AUDIT_HTTP_ENABLED, havingValue = TRUE)
    @ConditionalOnMissingBean(OpenApiSpecPathParameterService.class)
    public OpenApiSpecPathParameterService pathParameterService(
            final OpenApiSpecificationParser parser,
            final PathParameterNameExtractor nameExtractor,
            final PathParameterValueExtractor valueExtractor
    ) {
        return new OpenApiSpecPathParameterService(parser, nameExtractor, valueExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(AuditPayloadGenerationService.class)
    public AuditPayloadGenerationService auditPayloadGenerationService(
            @Qualifier(BEAN_OM) final ObjectMapper auditObjectMapper
    ) {
        return new AuditPayloadGenerationService(auditObjectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = AUDIT_HTTP_ENABLED, havingValue = TRUE)
    @ConditionalOnMissingBean(AuditFilter.class)
    public AuditFilter auditFilter(
            final AuditService auditService,
            final AuditPayloadGenerationService generator,
            final PathParameterService pathParameterService
    ) {
        return new AuditFilter(auditService, generator, pathParameterService);
    }

    private static void validateProps(final AuditProperties properties) {
        final List<String> hosts = properties.getHosts();
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalStateException("cp.audit.hosts must contain at least one broker host");
        }
        if (properties.getPort() <= 0) {
            throw new IllegalStateException("cp.audit.port must be a positive integer");
        }

        if (properties.isSslEnabled()) {
            final boolean hasTrust = hasLength(properties.getTruststore());
            final boolean hasKey   = hasLength(properties.getKeystore());
            if (!hasTrust && !hasKey) {
                throw new IllegalStateException(
                        "When ssl-enabled=true, set either cp.audit.truststore or cp.audit.keystore (will be reused as truststore).");
            }
            if (hasTrust && properties.getTruststorePassword() == null) {
                throw new IllegalStateException("cp.audit.truststore-password must be set when truststore is provided");
            }
            if (properties.isClientAuthRequired()) {
                if (!hasKey) {
                    throw new IllegalStateException(
                            "client-auth-required=true requires cp.audit.keystore and cp.audit.keystore-password");
                }
                if (properties.getKeystorePassword() == null) {
                    throw new IllegalStateException("cp.audit.keystore-password must be set when keystore is provided");
                }
            }
        }
    }

    private String buildHaConnectionUrl(final AuditProperties properties) {
        final JmsProperties jmsProps = properties.getJms();
        final boolean highAvailability = properties.isHa();

        final String common = String.join("&",
                "ha=" + (highAvailability ? "true" : "false"),
                "reconnectAttempts=" + jmsProps.getReconnectAttempts(),
                "initialConnectAttempts=" + jmsProps.getInitialConnectAttempts(),
                "retryInterval=" + jmsProps.getRetryIntervalMs(),
                "retryIntervalMultiplier=" + jmsProps.getRetryMultiplier(),
                "maxRetryInterval=" + jmsProps.getMaxRetryIntervalMs(),
                "connectionTtl=" + jmsProps.getConnectionTtlMs(),
                "callTimeout=" + jmsProps.getCallTimeoutMs(),
                "failoverOnInitialConnection=" + (highAvailability ? "true" : "false")
        );

        // Pre-size + clean appends; use chars for separators to satisfy PMD
        final StringBuilder ssl = new StringBuilder(160);
        if (properties.isSslEnabled()) {
            ssl.append("sslEnabled=true&verifyHost=").append(properties.isVerifyHost());

            final String trustPath = hasLength(properties.getTruststore())
                    ? properties.getTruststore() : properties.getKeystore();
            final String trustPass = hasLength(properties.getTruststore())
                    ? properties.getTruststorePassword() : properties.getKeystorePassword();

            ssl.append("&trustStorePath=").append(trustPath)
                    .append("&trustStorePassword=").append(trustPass);

            if (properties.isClientAuthRequired()) {
                ssl.append("&keyStorePath=").append(properties.getKeystore())
                        .append("&keyStorePassword=").append(properties.getKeystorePassword());
            }
            ssl.append('&');
        }

        final int port = properties.getPort();
        final StringJoiner urls = new StringJoiner(",");
        final String sslPart = ssl.toString();
        final StringBuilder urlBuilder = new StringBuilder(96);
        for (final String host : properties.getHosts()) {
            urlBuilder.setLength(0);
            urlBuilder.append("tcp://")
                    .append(host)
                    .append(':')
                    .append(port)
                    .append('?')
                    .append(sslPart)
                    .append(common);
            urls.add(urlBuilder.toString());
        }
        return urls.toString();
    }

    private void logSafeUrlSummary(final AuditProperties properties) {
        final boolean ssl = properties.isSslEnabled();
        final String hosts = String.join(",", properties.getHosts());
        final int port = properties.getPort();
        final JmsProperties jmsProps = properties.getJms();

        log.info(
                "Configuring Artemis connection: hosts={}, port={}, ssl={}, ha={}, reconnectAttempts={}, "
                        + "initialConnectAttempts={}, retryIntervalMs={}, retryMultiplier={}, maxRetryIntervalMs={}, "
                        + "connectionTtlMs={}, callTimeoutMs={}, verifyHost={}, clientAuthRequired={}",
                hosts, port, ssl, properties.isHa(),
                jmsProps.getReconnectAttempts(), jmsProps.getInitialConnectAttempts(), jmsProps.getRetryIntervalMs(),
                jmsProps.getRetryMultiplier(), jmsProps.getMaxRetryIntervalMs(), jmsProps.getConnectionTtlMs(),
                jmsProps.getCallTimeoutMs(), properties.isVerifyHost(), properties.isClientAuthRequired()
        );
    }
}
