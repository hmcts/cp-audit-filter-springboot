package uk.gov.hmcts.cp.filter.audit.model.structured;

import java.util.Map;
import java.util.UUID;

/**
 * All data needed to produce a RESPONSE-direction {@link AuditEvent}.
 *
 * <p>{@code requestId} must be the same UUID that was used for the paired
 * {@link AuditRequestContext} — this is what links the REQUEST and RESPONSE
 * audit events together.
 *
 * <p>{@code queryParams} and {@code pathParams} are carried forward from the
 * original request so the RESPONSE event contains the full interaction context.
 */
public record AuditResponseContext(
        UUID requestId,
        String serviceName,
        String serviceSubscriptionId,
        String method,
        String url,
        String clientIp,
        int statusCode,
        Map<String, String> headers,
        Map<String, String> queryParams,
        Map<String, String> pathParams,
        String body
) {
}
