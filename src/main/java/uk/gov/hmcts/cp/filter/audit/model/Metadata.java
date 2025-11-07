package uk.gov.hmcts.cp.filter.audit.model;

import java.util.Optional;
import java.util.UUID;

import lombok.Builder;

@SuppressWarnings("PMD.ShortVariable")
@Builder
public record Metadata(
        UUID id,
        String name,
        String createdAt,
        Optional<Correlation> correlation,
        Optional<Context> context
) {

    public record Correlation(String client) {
    }

    public record Context(String user) {
    }
}
