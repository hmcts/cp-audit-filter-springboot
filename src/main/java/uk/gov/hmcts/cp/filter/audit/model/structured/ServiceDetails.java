package uk.gov.hmcts.cp.filter.audit.model.structured;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * Identifies the service that produced the audit event.
 *
 * <p>{@code subscriptionId} is optional until service registration is in place;
 * set via {@code audit.http.subscription-id} config property per deployment.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceDetails(
        String name,
        String subscriptionId
) {
}
