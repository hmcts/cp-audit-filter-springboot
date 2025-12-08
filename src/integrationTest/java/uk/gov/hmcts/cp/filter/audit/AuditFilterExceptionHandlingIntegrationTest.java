package uk.gov.hmcts.cp.filter.audit;

import static java.util.UUID.randomUUID;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import uk.gov.hmcts.cp.filter.audit.config.ArtemisAuditAutoConfiguration;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.http.enabled=true",
                "audit.http.openapi-rest-spec=test-openapi.yaml",
                "server.servlet.context-path=/test-application"
        })
@AutoConfigureMockMvc
class AuditFilterExceptionHandlingIntegrationTest extends AbstractEmbeddedArtemisTest {

    private static final String TEST_USER_ID = randomUUID().toString();
    private static final String TEST_CLIENT_CORRELATION_ID = randomUUID().toString();

    private static final String TEST_CONTEXT_PATH = "test-application";
    private static final String CONTENT_TYPE = "application/json";
    private static final String HEADER_USER = "CJSCPPUID";
    private static final String HEADER_CORR = "CPPCLIENTCORRELATIONID";
    private static final String POST_PAYLOAD_BODY_VALUE = "test-request-" + randomUUID();

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @BeforeAll
    static void start() throws Exception {
        startEmbedded(false);
    }

    @Test
    void should_succeed_postRequest_whenArtemisUnavailable() throws Exception {
        stopBroker();

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
    }
}
