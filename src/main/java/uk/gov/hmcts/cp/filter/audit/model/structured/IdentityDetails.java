package uk.gov.hmcts.cp.filter.audit.model.structured;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * Identity of the caller extracted from well-known request headers.
 *
 * <p>Both fields are optional — they are {@code null} when the corresponding
 * header is absent from the request.
 *
 * <ul>
 *   <li>{@code userId} — from {@code CJSCPPUID} header</li>
 *   <li>{@code correlationId} — from {@code x-correlation-id} header (preferred)
 *       or {@code CPPCLIENTCORRELATIONID} header (fallback)</li>
 * </ul>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IdentityDetails(
        String userId,
        String correlationId
) {
}
