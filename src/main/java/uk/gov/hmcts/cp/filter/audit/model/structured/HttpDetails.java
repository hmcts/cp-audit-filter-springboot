package uk.gov.hmcts.cp.filter.audit.model.structured;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.UUID;

/**
 * HTTP envelope captured for every audit event.
 *
 * <p>{@code requestId} is generated once per HTTP request and stamped on both the
 * REQUEST and RESPONSE events — it is the key used to correlate the two events.
 *
 * <p>{@code statusCode} is {@code null} on REQUEST events (the response has not been
 * produced yet) and populated on RESPONSE events.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HttpDetails(
        UUID requestId,
        String method,
        String url,
        Integer statusCode,
        String clientIp
) {
}
