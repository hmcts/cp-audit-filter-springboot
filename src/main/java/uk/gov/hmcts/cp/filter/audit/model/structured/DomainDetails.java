package uk.gov.hmcts.cp.filter.audit.model.structured;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * Well-known HMCTS domain identifiers auto-promoted from path params, query params,
 * and headers when present on the request.
 *
 * <p>All fields are {@code null} when not applicable to the request.
 *
 * <ul>
 *   <li>{@code caseId}        — path or query param named {@code caseId}</li>
 *   <li>{@code caseUrn}       — path or query param named {@code caseUrn}</li>
 *   <li>{@code materialId}    — path or query param named {@code materialId}</li>
 *   <li>{@code clientId}      — {@code x-client-id} header</li>
 *   <li>{@code subscriptionId}— {@code x-subscription-id} header</li>
 * </ul>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DomainDetails(
        String caseId,
        String caseUrn,
        String materialId,
        String clientId,
        String subscriptionId
) {
}
