package uk.gov.hmcts.jms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuditMessageConsumer {
    @JmsListener(destination = "jms.topic.auditing.event")
    @SuppressWarnings("PMD.ShortMethodName")
    public void on(final String message) {
        log.info("Audit payload: {}", message);
    }
}
