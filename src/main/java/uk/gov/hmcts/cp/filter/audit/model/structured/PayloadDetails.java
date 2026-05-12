package uk.gov.hmcts.cp.filter.audit.model.structured;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.util.Map;

/**
 * Holds the path parameters, query parameters, and optionally the body of the
 * HTTP interaction.
 *
 * <p>{@code body} is {@code null} when body capture is suppressed
 * (i.e. {@code audit.http.include-payload-body=false}) to avoid capturing PII.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayloadDetails(
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        JsonNode body
) {
}
