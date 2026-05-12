package uk.gov.hmcts.cp.filter.audit.mapper;

import static java.util.UUID.randomUUID;

import uk.gov.hmcts.cp.filter.audit.model.structured.AuditEvent;
import uk.gov.hmcts.cp.filter.audit.model.structured.AuditRequestContext;
import uk.gov.hmcts.cp.filter.audit.model.structured.AuditResponseContext;
import uk.gov.hmcts.cp.filter.audit.model.structured.Direction;
import uk.gov.hmcts.cp.filter.audit.model.structured.DomainDetails;
import uk.gov.hmcts.cp.filter.audit.model.structured.HttpDetails;
import uk.gov.hmcts.cp.filter.audit.model.structured.IdentityDetails;
import uk.gov.hmcts.cp.filter.audit.model.structured.PayloadDetails;
import uk.gov.hmcts.cp.filter.audit.model.structured.ServiceDetails;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/**
 * Maps inbound HTTP interaction data into the proposed structured {@link AuditEvent} format.
 *
 * <p>Call {@link #toRequestEvent(AuditRequestContext)} when the request arrives and
 * {@link #toResponseEvent(AuditResponseContext)} when the response is ready. Both produced
 * events share the same {@code http.requestId} supplied by the caller, which is how the
 * Audit service links them.
 *
 * <p>This mapper is new code and has no impact on the existing {@code AuditPayloadGenerationService}
 * or the legacy {@code AuditPayload} structure.
 */
@RequiredArgsConstructor
public class AuditEventMapper {

    private static final String HEADER_USER_ID = "CJSCPPUID";
    private static final String HEADER_X_CORRELATION_ID = "x-correlation-id";
    private static final String HEADER_CPP_CORRELATION_ID = "CPPCLIENTCORRELATIONID";
    private static final String HEADER_CLIENT_ID = "x-client-id";
    private static final String HEADER_SUBSCRIPTION_ID = "x-subscription-id";

    private final ObjectMapper objectMapper;

    /**
     * When {@code false} the {@code payload.body} field is omitted from both REQUEST
     * and RESPONSE events to avoid capturing PII. Path and query params are always included.
     */
    private final boolean includePayloadBody;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Produces a REQUEST-direction {@link AuditEvent} from the supplied context.
     *
     * @param ctx data captured at the point the HTTP request was received
     * @return structured audit event ready for publishing to Artemis
     */
    public AuditEvent toRequestEvent(final AuditRequestContext ctx) {
        return AuditEvent.builder()
                .eventId(randomUUID())
                .eventType(AuditEvent.EVENT_TYPE_AUDIT_HTTP)
                .direction(Direction.REQUEST)
                .occurredAt(currentTimestamp())
                .service(buildService(ctx.serviceName(), ctx.serviceSubscriptionId()))
                .http(buildHttpForRequest(ctx))
                .identity(buildIdentity(ctx.headers()))
                .domain(buildDomain(ctx.headers(), ctx.pathParams(), ctx.queryParams()))
                .payload(buildPayload(ctx.pathParams(), ctx.queryParams(), ctx.body()))
                .build();
    }

    /**
     * Produces a RESPONSE-direction {@link AuditEvent} from the supplied context.
     *
     * <p>The {@code ctx.requestId()} must match the one used for the paired REQUEST event
     * so the two events can be correlated by the Audit service.
     *
     * @param ctx data captured at the point the HTTP response was returned
     * @return structured audit event ready for publishing to Artemis
     */
    public AuditEvent toResponseEvent(final AuditResponseContext ctx) {
        return AuditEvent.builder()
                .eventId(randomUUID())
                .eventType(AuditEvent.EVENT_TYPE_AUDIT_HTTP)
                .direction(Direction.RESPONSE)
                .occurredAt(currentTimestamp())
                .service(buildService(ctx.serviceName(), ctx.serviceSubscriptionId()))
                .http(buildHttpForResponse(ctx))
                .identity(buildIdentity(ctx.headers()))
                .domain(buildDomain(ctx.headers(), ctx.pathParams(), ctx.queryParams()))
                .payload(buildPayload(ctx.pathParams(), ctx.queryParams(), ctx.body()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Builders for each section
    // -------------------------------------------------------------------------

    private ServiceDetails buildService(final String name, final String subscriptionId) {
        return ServiceDetails.builder()
                .name(name)
                .subscriptionId(subscriptionId)
                .build();
    }

    private HttpDetails buildHttpForRequest(final AuditRequestContext ctx) {
        return HttpDetails.builder()
                .requestId(ctx.requestId())
                .method(ctx.method())
                .url(ctx.url())
                .statusCode(null)   // not yet known at request time
                .clientIp(ctx.clientIp())
                .build();
    }

    private HttpDetails buildHttpForResponse(final AuditResponseContext ctx) {
        return HttpDetails.builder()
                .requestId(ctx.requestId())
                .method(ctx.method())
                .url(ctx.url())
                .statusCode(ctx.statusCode())
                .clientIp(ctx.clientIp())
                .build();
    }

    private IdentityDetails buildIdentity(final Map<String, String> headers) {
        return IdentityDetails.builder()
                .userId(getHeader(headers, HEADER_USER_ID))
                .correlationId(getHeader(headers, HEADER_X_CORRELATION_ID, HEADER_CPP_CORRELATION_ID))
                .build();
    }

    private DomainDetails buildDomain(
            final Map<String, String> headers,
            final Map<String, String> pathParams,
            final Map<String, String> queryParams) {

        return DomainDetails.builder()
                .caseId(getParamFromPathOrQuery(pathParams, queryParams, "caseId"))
                .caseUrn(getParamFromPathOrQuery(pathParams, queryParams, "caseUrn"))
                .materialId(getParamFromPathOrQuery(pathParams, queryParams, "materialId"))
                .clientId(getHeader(headers, HEADER_CLIENT_ID))
                .subscriptionId(getHeader(headers, HEADER_SUBSCRIPTION_ID))
                .build();
    }

    private PayloadDetails buildPayload(
            final Map<String, String> pathParams,
            final Map<String, String> queryParams,
            final String body) {

        return PayloadDetails.builder()
                .pathParams(pathParams != null ? pathParams : Map.of())
                .queryParams(queryParams != null ? queryParams : Map.of())
                .body(includePayloadBody ? parseBody(body) : null)
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Looks up a header value case-insensitively, returning the first match across
     * the provided key candidates. {@code x-correlation-id} takes precedence over
     * {@code CPPCLIENTCORRELATIONID} because it is passed first in the varargs.
     */
    private String getHeader(final Map<String, String> headers, final String... keys) {
        if (headers == null) {
            return null;
        }
        for (final String key : keys) {
            for (final Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(key.trim())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Promotes a named identifier from path params first, then query params.
     * Returns {@code null} when the key is absent from both.
     */
    private String getParamFromPathOrQuery(
            final Map<String, String> pathParams,
            final Map<String, String> queryParams,
            final String key) {

        if (pathParams != null) {
            final String value = pathParams.get(key);
            if (value != null) {
                return value;
            }
        }
        if (queryParams != null) {
            return queryParams.get(key);
        }
        return null;
    }

    /**
     * Attempts to parse the body as JSON. If the body is blank or invalid JSON it
     * is wrapped as a plain text node so the audit event is never lost.
     */
    private JsonNode parseBody(final String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            return objectMapper.getNodeFactory().textNode(body);
        }
    }

    private String currentTimestamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
