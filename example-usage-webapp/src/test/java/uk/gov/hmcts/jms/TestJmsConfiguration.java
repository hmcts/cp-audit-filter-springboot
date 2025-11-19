package uk.gov.hmcts.jms;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.core.JmsTemplate;

@Configuration
public class TestJmsConfiguration {

    @Bean
    @Primary
    public JmsTemplate jmsTemplate(@Qualifier("auditJmsTemplate") JmsTemplate auditJmsTemplate) {
        return auditJmsTemplate;
    }
}
