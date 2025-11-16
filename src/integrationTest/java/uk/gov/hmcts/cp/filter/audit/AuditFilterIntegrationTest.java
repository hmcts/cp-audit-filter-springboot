package uk.gov.hmcts.cp.filter.audit;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import uk.gov.hmcts.cp.filter.audit.config.AuditAutoConfiguration;
import uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser;
import uk.gov.hmcts.cp.filter.audit.util.BrokerUtil;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.commons.lang3.StringEscapeUtils;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.activemq.ArtemisContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.http.enabled=true",
                // Use test OpenAPI spec defining in sample controller endpoints. This is needed for resolving path parameters
                "audit.http.openapi-rest-spec=test-openapi.yaml",
                "spring.jms.cache.enabled=false",
                "spring.jms.pub-sub-domain=true",
                "spring.artemis.mode=native",
                "server.servlet.context-path=/test-application"
        })
@AutoConfigureMockMvc
public class AuditFilterIntegrationTest {

    private static final String TEST_USER_ID = randomUUID().toString();
    private static final String TEST_CLIENT_CORRELATION_ID = randomUUID().toString();

    private static final String TEST_CONTEXT_PATH = "test-application";
    private static final String COMPONENT_NAME = TEST_CONTEXT_PATH + "-api";
    private static final String CONTENT_TYPE = "application/json";
    private static final String HEADER_ATTR_USER_ID = "CJSCPPUID";
    private static final String HEADER_ATTR_CLIENT_CORRELATION_ID = "CPPCLIENTCORRELATIONID";
    private static final String AUDIT_EVENT_NAME = "audit.events.audit-recorded";
    private static final String QUERY_PARAM_NAME = "query";
    private static final String QUERY_PARAM_VALUE = "param";
    private static final String POST_PAYLOAD_BODY_VALUE = "test-request" + randomUUID();

    private static String brokerUrl;

    public static final ArtemisContainer ARTEMIS_CONTAINER =
            new ArtemisContainer("apache/activemq-artemis:2.30.0-alpine")
                    .withEnv("ANONYMOUS_LOGIN", "true")
                    .withExposedPorts(61616, 8161); // Default Artemis and admin portal port


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
    void auditFilterShouldPublishPostRequestToArtemisTopic() throws Exception {

        try (BrokerUtil brokerUtil = new BrokerUtil(brokerUrl)) {

            final String apiPath = "/test-api/123/resource";
            mockMvc.perform(MockMvcRequestBuilders.post("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_ATTR_USER_ID, TEST_USER_ID)
                            .header(HEADER_ATTR_CLIENT_CORRELATION_ID, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .content("{\"data\": \"" + POST_PAYLOAD_BODY_VALUE + "\"}")) // Add a body to trigger response audit
                    .andExpect(MockMvcResultMatchers.status().isAccepted());

            String auditResponse = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("entity-id").asText().equals("123")
                            && getContentNode(json).get("data").asText().equals(POST_PAYLOAD_BODY_VALUE)
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(auditResponse);
        }
    }

    @Test
    void auditFilterShouldPublishPostRequestToArtemisTopicWhenErrorStatusReturned() throws Exception {

        try (BrokerUtil brokerUtil = new BrokerUtil(brokerUrl)) {

            final String apiPath = "/test-api/123/resource";
            mockMvc.perform(MockMvcRequestBuilders.post("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_ATTR_USER_ID, TEST_USER_ID)
                            .header(HEADER_ATTR_CLIENT_CORRELATION_ID, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .content("{\"data\": \"error payload\"}"))
                    .andExpect(MockMvcResultMatchers.status().is4xxClientError());

            String auditResponse = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("entity-id").asText().equals("123")
                            && getContentNode(json).get("data").asText().equals("error payload")
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(auditResponse);
        }
    }

    @Test
    void auditFilterShouldPublishGetRequestAndStringResponseToArtemisTopic() throws Exception {

        try (BrokerUtil brokerUtil = new BrokerUtil(brokerUrl)) {

            final String entityId = randomUUID().toString();
            final String apiPath = "/test-another-api/" + entityId + "/resource";
            mockMvc.perform(MockMvcRequestBuilders.get("/" + TEST_CONTEXT_PATH + apiPath)
                    .contextPath("/" + TEST_CONTEXT_PATH)
                    .servletPath(apiPath)
                    .header(HEADER_ATTR_USER_ID, TEST_USER_ID)
                    .header(HEADER_ATTR_CLIENT_CORRELATION_ID, TEST_CLIENT_CORRELATION_ID)
                    .contentType(CONTENT_TYPE)
                    .queryParam(QUERY_PARAM_NAME, QUERY_PARAM_VALUE)
            ).andExpect(MockMvcResultMatchers.status().isOk());

            String requestAuditMessage = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("another_entity_id").asText().equals(entityId)
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
                            && getContentNode(json).get("_payload").asText().equals("entity id = " + entityId)
                            && getAuditMetadataName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(responseAuditMessage);
        }
    }

    @Test
    void auditFilterShouldPublishGetRequestAndJsonResponseToArtemisTopic() throws Exception {

        try (BrokerUtil brokerUtil = new BrokerUtil(brokerUrl)) {

            final String entityId = randomUUID().toString();
            final String apiPath = "/test-yet-another-api/" + entityId + "/resource";
            mockMvc.perform(MockMvcRequestBuilders.get("/" + TEST_CONTEXT_PATH + apiPath)
                    .contextPath("/" + TEST_CONTEXT_PATH)
                    .servletPath(apiPath)
                    .header(HEADER_ATTR_USER_ID, TEST_USER_ID)
                    .header(HEADER_ATTR_CLIENT_CORRELATION_ID, TEST_CLIENT_CORRELATION_ID)
                    .contentType(CONTENT_TYPE)
                    .queryParam(QUERY_PARAM_NAME, QUERY_PARAM_VALUE)
            ).andExpect(MockMvcResultMatchers.status().isOk());

            String requestAuditMessage = brokerUtil.getMessageMatching(json ->
                    getOriginNode(json).asText().equals(TEST_CONTEXT_PATH)
                            && getComponentNode(json).asText().equals(COMPONENT_NAME)
                            && getUserNode(json).asText().equals(TEST_USER_ID)
                            && getClientCorrelationNode(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && getOperationName(json).asText().contains(CONTENT_TYPE)
                            && getContentNode(json).get("yet_another_entity_id").asText().equals(entityId)
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
                            && getContentNode(json).get("entityId").asText().equals(entityId)
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
        public ResponseEntity<Void> handlePost(@PathVariable("entity-id") String entityId, @RequestBody String body) {
            if (body.contains(POST_PAYLOAD_BODY_VALUE)) {
                return new ResponseEntity<>(HttpStatus.ACCEPTED);
            } else {
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

        }

        @GetMapping("/test-another-api/{another_entity_id}/resource")
        public ResponseEntity<String> handleGetReturnString(@PathVariable("another_entity_id") String anotherEntityId) {
            return new ResponseEntity<>("entity id = " + StringEscapeUtils.escapeHtml4(anotherEntityId), HttpStatus.OK);

        }

        @GetMapping("/test-yet-another-api/{another_entity_id}/resource")
        public ResponseEntity<String> handleGetReturnJson(@PathVariable("another_entity_id") String anotherEntityId) {
            final String payload = """
                    {
                      "entityId": "%s",
                      "message": "Data retrieved successfully."
                    }
                    """.formatted(StringEscapeUtils.escapeHtml4(anotherEntityId));
            return new ResponseEntity<>(payload, HttpStatus.OK);

        }


    }
}