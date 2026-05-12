package uk.gov.hmcts.cp.filter.audit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.filter.audit.config.ArtemisAuditAutoConfiguration;
import uk.gov.hmcts.cp.filter.audit.model.structured.AuditEvent;
import uk.gov.hmcts.cp.filter.audit.model.structured.Direction;
import uk.gov.hmcts.cp.filter.audit.model.structured.AuditRequestContext;
import uk.gov.hmcts.cp.filter.audit.model.structured.AuditResponseContext;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AuditEventMapperTest {

    private static final UUID REQUEST_ID = UUID.fromString("f7e6d5c4-0002-0000-0000-000000000000");
    private static final String SERVICE_NAME = "case-data-api";
    private static final String METHOD = "POST";
    private static final String URL = "/cases/CASE-001/documents";
    private static final String CLIENT_IP = "10.0.1.42";
    private static final String JSON_BODY = "{\"documentType\":\"CLAIM_FORM\",\"filename\":\"claim.pdf\"}";

    private AuditEventMapper mapperWithBody;
    private AuditEventMapper mapperWithoutBody;

    @BeforeEach
    void setUp() {
        final var objectMapper = new ArtemisAuditAutoConfiguration().auditObjectMapper();
        mapperWithBody = new AuditEventMapper(objectMapper, true);
        mapperWithoutBody = new AuditEventMapper(objectMapper, false);
    }

    // =========================================================================
    // REQUEST events
    // =========================================================================

    @Nested
    @DisplayName("toRequestEvent")
    class ToRequestEvent {

        @Test
        @DisplayName("should_populate_all_top_level_fields_for_a_request_event")
        void should_populate_all_top_level_fields_for_a_request_event() {
            final AuditEvent event = mapperWithBody.toRequestEvent(minimalRequestContext());

            assertThat(event.eventId()).isNotNull();
            assertThat(event.eventType()).isEqualTo(AuditEvent.EVENT_TYPE_AUDIT_HTTP);
            assertThat(event.direction()).isEqualTo(Direction.REQUEST);
            assertThat(event.occurredAt()).isNotBlank();
        }

        @Test
        @DisplayName("should_set_direction_to_REQUEST_not_RESPONSE")
        void should_set_direction_to_REQUEST_not_RESPONSE() {
            final AuditEvent event = mapperWithBody.toRequestEvent(minimalRequestContext());

            assertThat(event.direction()).isEqualTo(Direction.REQUEST);
        }

        @Test
        @DisplayName("should_populate_service_section_with_name_and_subscription_id")
        void should_populate_service_section_with_name_and_subscription_id() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, "svc-hmcts-case-data-001",
                    METHOD, URL, CLIENT_IP, Map.of(), Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.service().name()).isEqualTo(SERVICE_NAME);
            assertThat(event.service().subscriptionId()).isEqualTo("svc-hmcts-case-data-001");
        }

        @Test
        @DisplayName("should_populate_http_section_with_method_url_and_client_ip_but_no_status_code_on_request")
        void should_populate_http_section_with_method_url_and_client_ip_but_no_status_code_on_request() {
            final AuditEvent event = mapperWithBody.toRequestEvent(minimalRequestContext());

            assertThat(event.http().requestId()).isEqualTo(REQUEST_ID);
            assertThat(event.http().method()).isEqualTo(METHOD);
            assertThat(event.http().url()).isEqualTo(URL);
            assertThat(event.http().clientIp()).isEqualTo(CLIENT_IP);
            assertThat(event.http().statusCode()).isNull();
        }

        @Test
        @DisplayName("should_extract_userId_and_correlationId_into_identity_section")
        void should_extract_userId_and_correlationId_into_identity_section() {
            final Map<String, String> headers = Map.of(
                    "CJSCPPUID", "user-456",
                    "x-correlation-id", "corr-xyz-789"
            );
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP, headers, Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.identity().userId()).isEqualTo("user-456");
            assertThat(event.identity().correlationId()).isEqualTo("corr-xyz-789");
        }

        @Test
        @DisplayName("should_include_parsed_json_body_in_payload_when_body_capture_is_enabled")
        void should_include_parsed_json_body_in_payload_when_body_capture_is_enabled() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP, Map.of(), Map.of(), Map.of(), JSON_BODY);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.payload().body()).isNotNull();
            assertThat(event.payload().body().get("documentType").asText()).isEqualTo("CLAIM_FORM");
            assertThat(event.payload().body().get("filename").asText()).isEqualTo("claim.pdf");
        }

        @Test
        @DisplayName("should_omit_body_from_payload_but_retain_path_and_query_params_when_body_capture_is_disabled")
        void should_omit_body_from_payload_but_retain_path_and_query_params_when_body_capture_is_disabled() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(),
                    Map.of("caseType", "CIVIL"),
                    Map.of("caseId", "CASE-001"),
                    JSON_BODY);

            final AuditEvent event = mapperWithoutBody.toRequestEvent(ctx);

            assertThat(event.payload().body()).isNull();
            assertThat(event.payload().pathParams()).containsEntry("caseId", "CASE-001");
            assertThat(event.payload().queryParams()).containsEntry("caseType", "CIVIL");
        }
    }

    // =========================================================================
    // RESPONSE events
    // =========================================================================

    @Nested
    @DisplayName("toResponseEvent")
    class ToResponseEvent {

        @Test
        @DisplayName("should_set_direction_to_RESPONSE_not_REQUEST")
        void should_set_direction_to_RESPONSE_not_REQUEST() {
            final AuditEvent event = mapperWithBody.toResponseEvent(minimalResponseContext());

            assertThat(event.direction()).isEqualTo(Direction.RESPONSE);
        }

        @Test
        @DisplayName("should_populate_status_code_in_http_section_for_a_response_event")
        void should_populate_status_code_in_http_section_for_a_response_event() {
            final AuditEvent event = mapperWithBody.toResponseEvent(minimalResponseContext());

            assertThat(event.http().statusCode()).isEqualTo(201);
        }

        @Test
        @DisplayName("should_carry_the_same_request_id_as_the_request_event_to_link_the_two_events")
        void should_carry_the_same_request_id_as_the_request_event_to_link_the_two_events() {
            final AuditEvent requestEvent = mapperWithBody.toRequestEvent(minimalRequestContext());
            final AuditEvent responseEvent = mapperWithBody.toResponseEvent(minimalResponseContext());

            // Both events were built with REQUEST_ID so the Audit service can correlate them
            assertThat(requestEvent.http().requestId()).isEqualTo(REQUEST_ID);
            assertThat(responseEvent.http().requestId()).isEqualTo(REQUEST_ID);
        }

        @Test
        @DisplayName("should_include_response_body_in_payload_when_body_capture_is_enabled")
        void should_include_response_body_in_payload_when_body_capture_is_enabled() {
            final String responseBody = "{\"documentId\":\"DOC-9988\",\"status\":\"UPLOADED\"}";
            final AuditResponseContext ctx = new AuditResponseContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP, 201,
                    Map.of(), Map.of(), Map.of(), responseBody);

            final AuditEvent event = mapperWithBody.toResponseEvent(ctx);

            assertThat(event.payload().body()).isNotNull();
            assertThat(event.payload().body().get("documentId").asText()).isEqualTo("DOC-9988");
            assertThat(event.payload().body().get("status").asText()).isEqualTo("UPLOADED");
        }

        @Test
        @DisplayName("should_omit_body_from_payload_but_retain_path_and_query_params_when_body_capture_is_disabled")
        void should_omit_body_from_payload_but_retain_path_and_query_params_when_body_capture_is_disabled() {
            final AuditResponseContext ctx = new AuditResponseContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP, 201,
                    Map.of(),
                    Map.of("caseType", "CIVIL"),
                    Map.of("caseId", "CASE-001"),
                    "{\"documentId\":\"DOC-9988\"}");

            final AuditEvent event = mapperWithoutBody.toResponseEvent(ctx);

            assertThat(event.payload().body()).isNull();
            assertThat(event.payload().pathParams()).containsEntry("caseId", "CASE-001");
            assertThat(event.payload().queryParams()).containsEntry("caseType", "CIVIL");
        }
    }

    // =========================================================================
    // Domain auto-promotion
    // =========================================================================

    @Nested
    @DisplayName("domain field promotion")
    class DomainPromotion {

        @Test
        @DisplayName("should_promote_caseId_and_caseUrn_from_path_params_into_domain_section")
        void should_promote_caseId_and_caseUrn_from_path_params_into_domain_section() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(),
                    Map.of(),
                    Map.of("caseId", "CASE-001", "caseUrn", "URN:HMCTS:2026:001234"),
                    null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.domain().caseId()).isEqualTo("CASE-001");
            assertThat(event.domain().caseUrn()).isEqualTo("URN:HMCTS:2026:001234");
            assertThat(event.domain().materialId()).isNull();
        }

        @Test
        @DisplayName("should_promote_caseId_from_query_params_when_not_present_in_path_params")
        void should_promote_caseId_from_query_params_when_not_present_in_path_params() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(),
                    Map.of("caseId", "CASE-002"),
                    Map.of(),
                    null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.domain().caseId()).isEqualTo("CASE-002");
        }

        @Test
        @DisplayName("should_prefer_path_param_over_query_param_when_caseId_is_present_in_both")
        void should_prefer_path_param_over_query_param_when_caseId_is_present_in_both() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(),
                    Map.of("caseId", "CASE-FROM-QUERY"),
                    Map.of("caseId", "CASE-FROM-PATH"),
                    null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.domain().caseId()).isEqualTo("CASE-FROM-PATH");
        }

        @Test
        @DisplayName("should_promote_clientId_and_subscriptionId_from_headers_into_domain_section")
        void should_promote_clientId_and_subscriptionId_from_headers_into_domain_section() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of("x-client-id", "client-abc", "x-subscription-id", "sub-xyz"),
                    Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.domain().clientId()).isEqualTo("client-abc");
            assertThat(event.domain().subscriptionId()).isEqualTo("sub-xyz");
        }
    }

    // =========================================================================
    // Identity / correlation-id precedence
    // =========================================================================

    @Nested
    @DisplayName("identity header resolution")
    class IdentityHeaderResolution {

        @Test
        @DisplayName("should_use_x_correlation_id_when_only_that_header_is_present")
        void should_use_x_correlation_id_when_only_that_header_is_present() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of("x-correlation-id", "x-corr-123"),
                    Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.identity().correlationId()).isEqualTo("x-corr-123");
        }

        @Test
        @DisplayName("should_use_CPPCLIENTCORRELATIONID_when_x_correlation_id_is_absent")
        void should_use_CPPCLIENTCORRELATIONID_when_x_correlation_id_is_absent() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of("CPPCLIENTCORRELATIONID", "cpp-corr-456"),
                    Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.identity().correlationId()).isEqualTo("cpp-corr-456");
        }

        @Test
        @DisplayName("should_prefer_x_correlation_id_over_CPPCLIENTCORRELATIONID_when_both_headers_are_present")
        void should_prefer_x_correlation_id_over_CPPCLIENTCORRELATIONID_when_both_headers_are_present() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(
                            "x-correlation-id", "x-corr-wins",
                            "CPPCLIENTCORRELATIONID", "cpp-corr-loses"
                    ),
                    Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.identity().correlationId()).isEqualTo("x-corr-wins");
        }
    }

    // =========================================================================
    // Null / empty safety
    // =========================================================================

    @Nested
    @DisplayName("null and empty input handling")
    class NullSafety {

        @Test
        @DisplayName("should_handle_null_headers_without_throwing")
        void should_handle_null_headers_without_throwing() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    null, Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.identity().userId()).isNull();
            assertThat(event.identity().correlationId()).isNull();
        }

        @Test
        @DisplayName("should_handle_null_path_and_query_params_without_throwing")
        void should_handle_null_path_and_query_params_without_throwing() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(), null, null, null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.payload().pathParams()).isEmpty();
            assertThat(event.payload().queryParams()).isEmpty();
            assertThat(event.payload().body()).isNull();
        }

        @Test
        @DisplayName("should_wrap_invalid_json_body_as_plain_text_node_rather_than_dropping_it")
        void should_wrap_invalid_json_body_as_plain_text_node_rather_than_dropping_it() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(), Map.of(), Map.of(), "not-valid-json");

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.payload().body()).isNotNull();
            assertThat(event.payload().body().isTextual()).isTrue();
            assertThat(event.payload().body().asText()).isEqualTo("not-valid-json");
        }

        @Test
        @DisplayName("should_produce_null_body_node_when_body_string_is_null")
        void should_produce_null_body_node_when_body_string_is_null() {
            final AuditRequestContext ctx = new AuditRequestContext(
                    REQUEST_ID, SERVICE_NAME, null,
                    METHOD, URL, CLIENT_IP,
                    Map.of(), Map.of(), Map.of(), null);

            final AuditEvent event = mapperWithBody.toRequestEvent(ctx);

            assertThat(event.payload().body()).isNull();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private AuditRequestContext minimalRequestContext() {
        return new AuditRequestContext(
                REQUEST_ID, SERVICE_NAME, null,
                METHOD, URL, CLIENT_IP,
                Map.of(), Map.of(), Map.of(), JSON_BODY);
    }

    private AuditResponseContext minimalResponseContext() {
        return new AuditResponseContext(
                REQUEST_ID, SERVICE_NAME, null,
                METHOD, URL, CLIENT_IP, 201,
                Map.of(), Map.of(), Map.of(), "{\"documentId\":\"DOC-9988\"}");
    }
}
