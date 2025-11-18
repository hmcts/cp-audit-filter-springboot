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
import org.springframework.util.StringUtils;

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
    public ActiveMQConnectionFactory auditConnectionFactory(final AuditProperties props) {
        validateProps(props);
        final String url = buildHaConnectionUrl(props);
        logSafeUrlSummary(props);

        final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
        factory.setUser(Objects.toString(props.getUser(), ""));
        factory.setPassword(Objects.toString(props.getPassword(), ""));
        return factory;
    }

    @Bean(name = BEAN_JMS)
    @Primary
    @ConditionalOnMissingBean(name = BEAN_JMS)
    public JmsTemplate auditJmsTemplate(@Qualifier(BEAN_CF) final ActiveMQConnectionFactory amq,
                                        final AuditProperties props ) {
        final CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory(amq);
        cachingConnectionFactory.setSessionCacheSize(props.getJms().getSessionCacheSize());
        cachingConnectionFactory.setCacheProducers(true);
        cachingConnectionFactory.setReconnectOnException(true);
        final JmsTemplate jmsTemplate = new JmsTemplate(cachingConnectionFactory);
        jmsTemplate.setPubSubDomain(true);
        jmsTemplate.setDeliveryMode(DeliveryMode.PERSISTENT);
        jmsTemplate.setReceiveTimeout(5_000);
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
    public AuditService auditService(@Qualifier(BEAN_JMS) JmsTemplate jms,
                                     @Qualifier(BEAN_OM) ObjectMapper om) {
        return new AuditService(jms, om);
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
    public OpenApiSpecificationParser openApiSpecificationParser(final ClasspathResourceLoader loader,
                                                                 final OpenAPIParser openAPIParser,
                                                                 final HttpAuditProperties httpProps) {
        final OpenApiSpecificationParser parser =
                new OpenApiSpecificationParser(loader, httpProps.getOpenapiRestSpec(), openAPIParser, true);
        parser.init();
        return parser;
    }

    @Bean
    @ConditionalOnProperty(name = AUDIT_HTTP_ENABLED, havingValue = TRUE)
    @ConditionalOnMissingBean(OpenApiSpecPathParameterService.class)
    public OpenApiSpecPathParameterService pathParameterService(final OpenApiSpecificationParser parser,
                                                                final PathParameterNameExtractor nameExtractor,
                                                                final PathParameterValueExtractor valueExtractor) {
        return new OpenApiSpecPathParameterService(parser, nameExtractor, valueExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(AuditPayloadGenerationService.class)
    public AuditPayloadGenerationService auditPayloadGenerationService(
            @Qualifier(BEAN_OM) final ObjectMapper auditObjectMapper) {
        return new AuditPayloadGenerationService(auditObjectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = AUDIT_HTTP_ENABLED, havingValue = TRUE)
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
            // You can provide either a truststore OR a keystore (used as truststore).
            final boolean hasTrust = hasLength(props.getTruststore());
            final boolean hasKey   = hasLength(props.getKeystore());
            if (!hasTrust && !hasKey) {
                throw new IllegalStateException(
                        "When ssl-enabled=true, set either cp.audit.truststore or cp.audit.keystore (will be reused as truststore).");
            }
            if (hasTrust && props.getTruststorePassword() == null) {
                throw new IllegalStateException("cp.audit.truststore-password must be set when truststore is provided");
            }
            if (props.isClientAuthRequired()) {
                if (!hasKey) {
                    throw new IllegalStateException(
                            "client-auth-required=true requires cp.audit.keystore and cp.audit.keystore-password");
                }
                if (props.getKeystorePassword() == null) {
                    throw new IllegalStateException("cp.audit.keystore-password must be set when keystore is provided");
                }
            }
        }
    }

    private String buildHaConnectionUrl(final AuditProperties props) {
        final JmsProperties jms = props.getJms();
        final boolean ha = props.isHa();

        final String common = String.join("&",
                "ha=" + (ha ? "true" : "false"),
                "reconnectAttempts=" + jms.getReconnectAttempts(),
                "initialConnectAttempts=" + jms.getInitialConnectAttempts(),
                "retryInterval=" + jms.getRetryIntervalMs(),
                "retryIntervalMultiplier=" + jms.getRetryMultiplier(),
                "maxRetryInterval=" + jms.getMaxRetryIntervalMs(),
                "connectionTtl=" + jms.getConnectionTtlMs(),
                "callTimeout=" + jms.getCallTimeoutMs(),
                "failoverOnInitialConnection=" + (ha ? "true" : "false")
        );

        final StringBuilder ssl = new StringBuilder();
        if (props.isSslEnabled()) {
            ssl.append("sslEnabled=true");
            ssl.append("&verifyHost=").append(props.isVerifyHost());

            final String trustPath = hasLength(props.getTruststore()) ? props.getTruststore() : props.getKeystore();
            final String trustPass = hasLength(props.getTruststore()) ? props.getTruststorePassword() : props.getKeystorePassword();

            ssl.append("&trustStorePath=").append(trustPath);
            ssl.append("&trustStorePassword=").append(trustPass);

            if (props.isClientAuthRequired()) {
                ssl.append("&keyStorePath=").append(props.getKeystore());
                ssl.append("&keyStorePassword=").append(props.getKeystorePassword());
            }
            ssl.append("&");
        }

        final int port = props.getPort();
        final StringJoiner urls = new StringJoiner(",");
        for (final String host : props.getHosts()) {
            urls.add("tcp://" + host + ':' + port + '?' + ssl + common);
        }
        return urls.toString();
    }

    private void logSafeUrlSummary(final AuditProperties props) {
        final boolean ssl = props.isSslEnabled();
        final String hosts = String.join(",", props.getHosts());
        final int port = props.getPort();
        final JmsProperties jms = props.getJms();

        log.info("Configuring Artemis connection: hosts={}, port={}, ssl={}, ha={}, " +
                        "reconnectAttempts={}, initialConnectAttempts={}, retryIntervalMs={}, " +
                        "retryMultiplier={}, maxRetryIntervalMs={}, connectionTtlMs={}, callTimeoutMs={}, verifyHost={}, clientAuthRequired={}",
                hosts, port, ssl, props.isHa(),
                jms.getReconnectAttempts(), jms.getInitialConnectAttempts(), jms.getRetryIntervalMs(),
                jms.getRetryMultiplier(), jms.getMaxRetryIntervalMs(), jms.getConnectionTtlMs(),
                jms.getCallTimeoutMs(), props.isVerifyHost(), props.isClientAuthRequired());
    }
}
