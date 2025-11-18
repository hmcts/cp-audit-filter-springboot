package uk.gov.hmcts.cp.filter.audit;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.http.enabled=true",
                "audit.http.openapi-rest-spec=test-openapi.yaml",
                "server.servlet.context-path=/test-application"
        })
@AutoConfigureMockMvc
class AuditFilterEmbeddedSslKeystoreIntegrationTest extends AbstractEmbeddedArtemisTest {

    private static final String TEST_USER_ID = randomUUID().toString();
    private static final String TEST_CLIENT_CORR = randomUUID().toString();
    private static final String CTX = "test-application";
    private static final String COMPONENT = CTX + "-api";
    private static final String CONTENT_TYPE = "application/json";

    @Autowired private MockMvc mockMvc;

    static {
        setSslEnabledStatic(true);
        setKeystoreOnlyStatic(true);
    }

    @BeforeAll
    static void boot() throws Exception {
        startEmbedded(true);
    }

    @Test
    void publishes_over_tls_using_keystore_only() throws Exception {
        try (var broker = uk.gov.hmcts.cp.filter.audit.util.BrokerUtil.builder(brokerUrlForConsumer)
                .waitFor(consumerWait())
                .build()) {

            final String apiPath = "/test-api/123/resource";
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/" + CTX + apiPath)
                            .contextPath("/" + CTX)
                            .servletPath(apiPath)
                            .header("CJSCPPUID", TEST_USER_ID)
                            .header("CPPCLIENTCORRELATIONID", TEST_CLIENT_CORR)
                            .contentType(CONTENT_TYPE)
                            .content("{\"data\":\"test-request\"}"))
                    .andExpect(status().isAccepted());

            final String msg = broker.getMessageMatching(json ->
                    origin(json).asText().equals(CTX) &&
                            component(json).asText().equals(COMPONENT) &&
                            user(json).asText().equals(TEST_USER_ID) &&
                            corr(json).asText().equals(TEST_CLIENT_CORR) &&
                            opName(json).asText().contains(CONTENT_TYPE) &&
                            content(json).get("entity-id").asText().equals("123") &&
                            content(json).get("data").asText().equals("test-request") &&
                            mdName(json).asText().equals("audit.events.audit-recorded")
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
        public ResponseEntity<Void> post() {
            return new ResponseEntity<>(HttpStatus.ACCEPTED);
        }
    }
}
