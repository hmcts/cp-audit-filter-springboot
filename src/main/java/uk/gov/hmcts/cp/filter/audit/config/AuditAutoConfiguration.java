// Inside your audit-facade-starter module package (e.g., uk.gov.moj.cpp.filter.audit.config)

package uk.gov.hmcts.cp.filter.audit.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("uk.gov.hmcts.cp.filter.audit") // Explicitly scan the filter's package
public class AuditAutoConfiguration {
    // This class ensures that all components, like AuditFilter,
    // within the uk.gov.moj.cpp.filter.audit package are found.
}