package uk.gov.hmcts.cp.filter.audit.model.structured;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.UUID;

/**
 * Top-level structured audit event produced for every HTTP request and response
 * intercepted by the audit filter.
 *
 * <p>Every event has the same six top-level sections regardless of direction.
 * REQUEST and RESPONSE events for the same HTTP interaction share the same
 * {@code http.requestId}.
 *
 * <p>This is the proposed replacement for the legacy {@code AuditPayload}.
 * See {@code docs/audit-payload-proposed-structure.md} for the full proposal.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
        UUID eventId,
        String eventType,
        Direction direction,
        String occurredAt,
        ServiceDetails service,
        HttpDetails http,
        IdentityDetails identity,
        DomainDetails domain,
        PayloadDetails payload
) {
    /** Fixed event type value — stable for consumer topic subscriptions. */
    public static final String EVENT_TYPE_AUDIT_HTTP = "AUDIT_HTTP";
}
