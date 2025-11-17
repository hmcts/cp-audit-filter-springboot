package uk.gov.hmcts.cp.filter.audit;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.filter.audit.util.BrokerUtil.builder;

import uk.gov.hmcts.cp.filter.audit.config.ArtemisAuditAutoConfiguration;
import uk.gov.hmcts.cp.filter.audit.util.BrokerUtil;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.http.enabled=true",
                "audit.http.openapi-rest-spec=test-openapi.yaml",
                "server.servlet.context-path=/test-application"
        })
@AutoConfigureMockMvc
class AuditFilterEmbeddedPlainIntegrationTest extends AbstractEmbeddedArtemisTest {

    private static final String TEST_USER_ID = randomUUID().toString();
    private static final String TEST_CLIENT_CORRELATION_ID = randomUUID().toString();

    private static final String TEST_CONTEXT_PATH = "test-application";
    private static final String COMPONENT_NAME = TEST_CONTEXT_PATH + "-api";
    private static final String CONTENT_TYPE = "application/json";
    private static final String HEADER_USER = "CJSCPPUID";
    private static final String HEADER_CORR = "CPPCLIENTCORRELATIONID";
    private static final String AUDIT_EVENT_NAME = "audit.events.audit-recorded";

    private static final String QUERY_PARAM_NAME = "query";
    private static final String QUERY_PARAM_VALUE = "param";
    private static final String POST_PAYLOAD_BODY_VALUE = "test-request-" + randomUUID();

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @BeforeAll
    static void start() throws Exception {
        startEmbedded(false); // Plain (non-SSL) embedded broker
    }

    @Test
    void should_publish_post_accept_to_embedded_plain_broker() throws Exception {
        try (BrokerUtil broker = builder(brokerUrlForConsumer).waitFor(consumerWait()).build()) {
            final String apiPath = "/test-api/123/resource";

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_USER, TEST_USER_ID)
                            .header(HEADER_CORR, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .content("{\"data\":\"" + POST_PAYLOAD_BODY_VALUE + "\"}"))
                    .andExpect(status().isAccepted());

            final String msg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(TEST_CONTEXT_PATH)
                            && component(json).asText().equals(COMPONENT_NAME)
                            && user(json).asText().equals(TEST_USER_ID)
                            && corr(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && opName(json).asText().contains(CONTENT_TYPE)
                            && content(json).get("entity-id").asText().equals("123")
                            && content(json).get("data").asText().equals(POST_PAYLOAD_BODY_VALUE)
                            && mdName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(msg);
        }
    }

    @Test
    void should_publish_post_when_error_status_returned() throws Exception {
        try (BrokerUtil broker = builder(brokerUrlForConsumer).waitFor(consumerWait()).build()) {
            final String apiPath = "/test-api/123/resource";

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_USER, TEST_USER_ID)
                            .header(HEADER_CORR, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .content("{\"data\":\"error payload\"}"))
                    .andExpect(status().is4xxClientError());

            final String msg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(TEST_CONTEXT_PATH)
                            && component(json).asText().equals(COMPONENT_NAME)
                            && user(json).asText().equals(TEST_USER_ID)
                            && corr(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && opName(json).asText().contains(CONTENT_TYPE)
                            && content(json).get("entity-id").asText().equals("123")
                            && content(json).get("data").asText().equals("error payload")
                            && mdName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(msg);
        }
    }

    @Test
    void should_publish_get_and_string_response_to_embedded_plain_broker() throws Exception {
        try (BrokerUtil broker = builder(brokerUrlForConsumer).waitFor(consumerWait()).build()) {
            final String entityId = randomUUID().toString();
            final String apiPath = "/test-another-api/" + entityId + "/resource";

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_USER, TEST_USER_ID)
                            .header(HEADER_CORR, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .queryParam(QUERY_PARAM_NAME, QUERY_PARAM_VALUE))
                    .andExpect(status().isOk());

            final String requestMsg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(TEST_CONTEXT_PATH)
                            && component(json).asText().equals(COMPONENT_NAME)
                            && user(json).asText().equals(TEST_USER_ID)
                            && corr(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && opName(json).asText().contains(CONTENT_TYPE)
                            && content(json).get("another_entity_id").asText().equals(entityId)
                            && content(json).get(QUERY_PARAM_NAME).asText().equals(QUERY_PARAM_VALUE)
                            && mdName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(requestMsg);

            final String responseMsg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(TEST_CONTEXT_PATH)
                            && component(json).asText().equals(COMPONENT_NAME)
                            && user(json).asText().equals(TEST_USER_ID)
                            && corr(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && opName(json).asText().contains(CONTENT_TYPE)
                            && content(json).get("_payload").asText().equals("entity id = " + entityId)
                            && mdName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(responseMsg);
        }
    }

    @Test
    void should_publish_get_and_json_response_to_embedded_plain_broker() throws Exception {
        try (BrokerUtil broker = builder(brokerUrlForConsumer).waitFor(consumerWait()).build()) {
            final String entityId = randomUUID().toString();
            final String apiPath = "/test-yet-another-api/" + entityId + "/resource";

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_USER, TEST_USER_ID)
                            .header(HEADER_CORR, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .queryParam(QUERY_PARAM_NAME, QUERY_PARAM_VALUE))
                    .andExpect(status().isOk());

            final String requestMsg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(TEST_CONTEXT_PATH)
                            && component(json).asText().equals(COMPONENT_NAME)
                            && user(json).asText().equals(TEST_USER_ID)
                            && corr(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && opName(json).asText().contains(CONTENT_TYPE)
                            && content(json).get("yet_another_entity_id").asText().equals(entityId)
                            && content(json).get(QUERY_PARAM_NAME).asText().equals(QUERY_PARAM_VALUE)
                            && mdName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(requestMsg);

            final String responseMsg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(TEST_CONTEXT_PATH)
                            && component(json).asText().equals(COMPONENT_NAME)
                            && user(json).asText().equals(TEST_USER_ID)
                            && corr(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && opName(json).asText().contains(CONTENT_TYPE)
                            && content(json).get("entityId").asText().equals(entityId)
                            && content(json).get("message").asText().equals("Data retrieved successfully.")
                            && mdName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(responseMsg);
        }
    }

    // ---- Minimal app to exercise the filter ----
    @Configuration
    @EnableAutoConfiguration(exclude = {JmsAutoConfiguration.class})
    @Import({ArtemisAuditAutoConfiguration.class})
    @RestController
    static class HarnessApp {

        @PostMapping("/test-api/{entity-id}/resource")
        public ResponseEntity<Void> post(@PathVariable("entity-id") String entityId,
                                         @RequestBody String body) {
            if (body != null && body.contains("error payload")) {
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            return (body != null && body.contains(POST_PAYLOAD_BODY_VALUE))
                    ? new ResponseEntity<>(HttpStatus.ACCEPTED)
                    : new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        @GetMapping("/test-another-api/{another_entity_id}/resource")
        public ResponseEntity<String> getString(@PathVariable("another_entity_id") String id) {
            // Use Spring's HtmlUtils to avoid deprecated commons-lang3 escapes
            return new ResponseEntity<>("entity id = " + HtmlUtils.htmlEscape(id), HttpStatus.OK);
        }

        @GetMapping("/test-yet-another-api/{another_entity_id}/resource")
        public ResponseEntity<String> getJson(@PathVariable("another_entity_id") String id) {
            final String payload = """
                    {
                      "entityId": "%s",
                      "message": "Data retrieved successfully."
                    }
                    """.formatted(HtmlUtils.htmlEscape(id));
            return new ResponseEntity<>(payload, HttpStatus.OK);
        }
    }
}
