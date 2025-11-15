package uk.gov.hmcts.cp.filter.audit;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import uk.gov.hmcts.cp.filter.audit.config.AuditAutoConfiguration;
import uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser;
import uk.gov.hmcts.cp.filter.audit.util.BrokerUtil;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.activemq.ArtemisContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.http.enabled=true",
                // Use test OpenAPI spec defining in sample controller endpoints. This is needed for resolving path parameters
                "audit.http.openapi-rest-spec=test-openapi.yaml",
                "spring.jms.cache.enabled=false",
                "spring.jms.pub-sub-domain=true",
                "spring.artemis.mode=native",
                "spring.main.allow-bean-definition-overriding=true",
                "server.servlet.context-path=/test-application"
        })
@AutoConfigureMockMvc
public class AuditFilterIntegrationTest {

    private static final String TEST_USER_ID = randomUUID().toString();
    private static final String TEST_CLIENT_CORRELATION_ID = randomUUID().toString();

    private static final String TEST_CONTEXT_PATH = "test-application";
    protected static final String COMPONENT_NAME = TEST_CONTEXT_PATH + "-api";
    protected static final String CONTENT_TYPE = "application/json";
    protected static final String HEADER_ATTR_USER_ID = "CJSCPPUID";
    protected static final String HEADER_ATTR_CLIENT_CORRELATION_ID = "CPPCLIENTCORRELATIONID";
    protected static final String AUDIT_EVENT_NAME = "audit.events.audit-recorded";
    protected static final String QUERY_PARAM_NAME = "query";
    protected static final String QUERY_PARAM_VALUE = "param";


    private static String brokerUrl;


    public static final ArtemisContainer ARTEMIS_CONTAINER =
            new ArtemisContainer("apache/activemq-artemis:2.30.0-alpine")
                    .withEnv("ANONYMOUS_LOGIN", "true")
                    .withExposedPorts(61616, 8161); // Default Artemis and admin portal port


    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpenApiSpecificationParser openApiSpecificationParser;

    @BeforeAll
    static void startContainer() {
        ARTEMIS_CONTAINER.start();
    }

    @AfterAll
    static void stopContainer() {
        ARTEMIS_CONTAINER.stop();
    }

    @DynamicPropertySource
    static void registerArtemisProperties(DynamicPropertyRegistry registry) {
        brokerUrl = "tcp://localhost:" + ARTEMIS_CONTAINER.getMappedPort(61616);
        registry.add("spring.jms.broker-url", () -> brokerUrl);
    }


    @Test
    void auditFilter_shouldPublishPostRequestToArtemisTopic() throws Exception {

        try (BrokerUtil brokerUtil = new BrokerUtil(brokerUrl)) {

            final String apiPath = "/test-api/123/resource";
            mockMvc.perform(post("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_ATTR_USER_ID, TEST_USER_ID)
                            .header(HEADER_ATTR_CLIENT_CORRELATION_ID, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .content("{\"data\": \"test-request\"}")) // Add a body to trigger response audit
                    .andExpect(status().isAccepted());

            String auditResponse = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("entity-id").asText().equals("123")
                            && getContentNode(json).get("data").asText().equals("test-request")
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(auditResponse);
        }
    }

    @Test
    void auditFilterGetRequest_publishRequestAndStringResponseToArtemisTopic() throws Exception {

        try (BrokerUtil brokerUtil = new BrokerUtil(brokerUrl)) {

            final String apiPath = "/test-another-api/123/resource";
            mockMvc.perform(get("/" + TEST_CONTEXT_PATH + apiPath)
                    .contextPath("/" + TEST_CONTEXT_PATH)
                    .servletPath(apiPath)
                    .header(HEADER_ATTR_USER_ID, TEST_USER_ID)
                    .header(HEADER_ATTR_CLIENT_CORRELATION_ID, TEST_CLIENT_CORRELATION_ID)
                    .contentType(CONTENT_TYPE)
                    .queryParam(QUERY_PARAM_NAME, QUERY_PARAM_VALUE)
            ).andExpect(status().isOk());

            String requestAuditMessage = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("another_entity_id").asText().equals("123")
                            && getContentNode(json).get(QUERY_PARAM_NAME).asText().equals(QUERY_PARAM_VALUE)
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(requestAuditMessage);

            String responseAuditMessage = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("_payload").asText().equals("test response")
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(responseAuditMessage);
        }
    }

    @Test
    void auditFilterGetRequest_publishRequestAndJsonResponseToArtemisTopic() throws Exception {

        try (BrokerUtil brokerUtil = new BrokerUtil(brokerUrl)) {

            final String apiPath = "/test-yet-another-api/123/resource";
            mockMvc.perform(get("/" + TEST_CONTEXT_PATH + apiPath)
                    .contextPath("/" + TEST_CONTEXT_PATH)
                    .servletPath(apiPath)
                    .header(HEADER_ATTR_USER_ID, TEST_USER_ID)
                    .header(HEADER_ATTR_CLIENT_CORRELATION_ID, TEST_CLIENT_CORRELATION_ID)
                    .contentType(CONTENT_TYPE)
                    .queryParam(QUERY_PARAM_NAME, QUERY_PARAM_VALUE)
            ).andExpect(status().isOk());

            String requestAuditMessage = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("yet_another_entity_id").asText().equals("123")
                            && getContentNode(json).get(QUERY_PARAM_NAME).asText().equals(QUERY_PARAM_VALUE)
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(requestAuditMessage);

            String responseAuditMessage = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("status").asText().equals("success")
                            && getContentNode(json).get("message").asText().equals("Data retrieved successfully.")
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(responseAuditMessage);
        }
    }

    private static JsonNode getAuditMetadataName(final JsonNode json) {
        return getAuditMetadata(json).get("name");
    }

    private static JsonNode getAuditMetadata(final JsonNode json) {
        return json.get("_metadata");
    }

    private JsonNode getOperationName(final JsonNode json) {
        return getContentMetadataNode(json).get("name");
    }

    private static JsonNode getContentNode(final JsonNode json) {
        return json.get("content");
    }

    private static JsonNode getComponentNode(final JsonNode json) {
        return json.get("component");
    }

    private static JsonNode getOriginNode(final JsonNode json) {
        return json.get("origin");
    }

    private JsonNode getClientCorrelationNode(final JsonNode json) {
        return getContentMetadataNode(json).get("correlation").get("client");
    }

    private JsonNode getContentMetadataNode(final JsonNode json) {
        return getAuditMetadata(getContentNode(json));
    }

    private JsonNode getUserNode(final JsonNode json) {
        return getContentMetadataNode(json).get("context").get("user");
    }

    // Minimal Spring Boot Application config
    @Configuration
    @EnableAutoConfiguration(exclude = {JmsAutoConfiguration.class})
    @Import({AuditAutoConfiguration.class})
    @RestController
    public static class AuditEnabledApplication {

        @Bean
        public ConnectionFactory connectionFactory() {
            return new ActiveMQConnectionFactory(brokerUrl);
        }

        @Bean
        public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
            JmsTemplate template = new JmsTemplate(connectionFactory);
            template.setPubSubDomain(true);
            return template;
        }

        @PostMapping("/test-api/{entity-id}/resource")
        public ResponseEntity<Void> handlePost(@RequestBody String body) {
            return new ResponseEntity<>(HttpStatus.ACCEPTED);

        }

        @GetMapping("/test-another-api/{another_entity_id}/resource")
        public ResponseEntity<String> handleGetReturnString() {
            return new ResponseEntity<>("test response", HttpStatus.OK);

        }

        @GetMapping("/test-yet-another-api/{another_entity_id}/resource")
        public ResponseEntity<String> handleGetReturnJson() {
            final String payload = """
                    {
                      "status": "success",
                      "message": "Data retrieved successfully."
                    }
                    """;
            return new ResponseEntity<>(payload, HttpStatus.OK);

        }
    }
}