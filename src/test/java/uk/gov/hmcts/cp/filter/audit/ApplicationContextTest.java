package uk.gov.hmcts.cp.filter.audit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import uk.gov.hmcts.cp.filter.audit.config.AuditAutoConfiguration;

import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = {AuditAutoConfiguration.class, ApplicationContextTest.ConfigMocks.class})
@TestPropertySource(properties = {
        "audit.http.openapi-rest-spec=test-openapi.yaml",
        "audit.http.enabled=true",
        "spring.main.allow-bean-definition-overriding=true"
})
class ApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // If the context fails to start, this test will fail automatically
        assertNotNull(applicationContext, "ApplicationContext should be loaded");
    }

    @Configuration
    /* default */
    static class ConfigMocks {
        @Bean
        public ConnectionFactory connectionFactory() {
            return new ActiveMQConnectionFactory("tcp://localhost:61616");
        }

        @Bean
        public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
            JmsTemplate template = new JmsTemplate(connectionFactory);
            template.setPubSubDomain(true);
            return template;
        }
    }
}
