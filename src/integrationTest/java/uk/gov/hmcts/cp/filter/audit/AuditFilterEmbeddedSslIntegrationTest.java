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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.http.enabled=true",
                "audit.http.openapi-rest-spec=test-openapi.yaml",
                "server.servlet.context-path=/test-application"
        })
@AutoConfigureMockMvc
class AuditFilterEmbeddedSslIntegrationTest extends AbstractEmbeddedArtemisTest {

    private static final String TEST_USER_ID = randomUUID().toString();
    private static final String TEST_CLIENT_CORRELATION_ID = randomUUID().toString();
    private static final String TEST_CONTEXT_PATH = "test-application";
    private static final String COMPONENT_NAME = TEST_CONTEXT_PATH + "-api";
    private static final String CONTENT_TYPE = "application/json";
    private static final String HEADER_USER = "CJSCPPUID";
    private static final String HEADER_CORR = "CPPCLIENTCORRELATIONID";
    private static final String AUDIT_EVENT_NAME = "audit.events.audit-recorded";

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @BeforeAll
    static void start() throws Exception {
        startEmbedded(true);
    }

    @Test
    void should_publish_to_embedded_ssl_broker() throws Exception {
        try (BrokerUtil broker = builder(brokerUrlForConsumer)
                .waitFor(consumerWait())
                .build()) {

            final String apiPath = "/test-api/123/resource";
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/" + TEST_CONTEXT_PATH + apiPath)
                            .contextPath("/" + TEST_CONTEXT_PATH)
                            .servletPath(apiPath)
                            .header(HEADER_USER, TEST_USER_ID)
                            .header(HEADER_CORR, TEST_CLIENT_CORRELATION_ID)
                            .contentType(CONTENT_TYPE)
                            .content("{\"data\":\"test-request\"}"))
                    .andExpect(status().isAccepted());

            String msg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(TEST_CONTEXT_PATH)
                            && component(json).asText().equals(COMPONENT_NAME)
                            && user(json).asText().equals(TEST_USER_ID)
                            && corr(json).asText().equals(TEST_CLIENT_CORRELATION_ID)
                            && opName(json).asText().contains(CONTENT_TYPE)
                            && content(json).get("entity-id").asText().equals("123")
                            && content(json).get("data").asText().equals("test-request")
                            && mdName(json).asText().equals(AUDIT_EVENT_NAME)
            );
            assertNotNull(msg);
        }
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {JmsAutoConfiguration.class})
    @Import({ArtemisAuditAutoConfiguration.class})
    @RestController
    static class HarnessApp {
        @PostMapping("/test-api/{entity-id}/resource")
        public ResponseEntity<Void> post(@RequestBody String body) {
            return new ResponseEntity<>(HttpStatus.ACCEPTED);
        }
    }
}
