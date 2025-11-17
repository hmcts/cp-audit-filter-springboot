package uk.gov.hmcts.cp.filter.audit.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "audit.http")
public class HttpAuditProperties {

    /**
     * Enable/disable HTTP auditing filter and OpenAPI parsing.
     */
    private boolean enabled;

    /**
     * Classpath pattern to locate OpenAPI spec (e.g. "openapi.yaml" or "openapi/*.yaml").
     */
    private String openapiRestSpec;
}
