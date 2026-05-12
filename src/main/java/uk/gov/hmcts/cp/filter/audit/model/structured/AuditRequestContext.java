package uk.gov.hmcts.cp.filter.audit.model.structured;

import java.util.Map;
import java.util.UUID;

/**
 * All data needed to produce a REQUEST-direction {@link AuditEvent}.
 *
 * <p>{@code requestId} must be generated once per HTTP request by the caller and
 * shared with the corresponding {@link AuditResponseContext} so that the two audit
 * events can be linked by {@code http.requestId}.
 *
 * <p>{@code clientIp} should prefer the {@code X-Forwarded-For} header value when
 * the service runs behind a proxy, falling back to {@code request.getRemoteAddr()}.
 */
public record AuditRequestContext(
        UUID requestId,
        String serviceName,
        String serviceSubscriptionId,
        String method,
        String url,
        String clientIp,
        Map<String, String> headers,
        Map<String, String> queryParams,
        Map<String, String> pathParams,
        String body
) {
}
