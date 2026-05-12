package uk.gov.hmcts.cp.filter.audit.model.structured;

/**
 * Distinguishes whether an audit event was produced at the point a request
 * was received ({@code REQUEST}) or at the point a response was sent ({@code RESPONSE}).
 *
 * <p>REQUEST and RESPONSE events are linked by the same {@code http.requestId}.
 */
public enum Direction {
    REQUEST,
    RESPONSE
}
