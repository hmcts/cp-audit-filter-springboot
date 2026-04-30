package uk.gov.hmcts.cp.filter.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.model.RequestInfo;
import uk.gov.hmcts.cp.filter.audit.model.ResponseInfo;
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.filter.audit.service.AuditService;
import uk.gov.hmcts.cp.filter.audit.service.PathParameterService;
import uk.gov.hmcts.cp.filter.audit.wrapper.AuditServletRequestWrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

class AuditFilterTest {

    private AuditFilter auditFilter;

    private AuditService mockAuditService;
    private AuditPayloadGenerationService mockAuditPayloadGenerationService;

    private MockHttpServletRequest mockRequest;
    private MockHttpServletResponse mockResponse;
    private FilterChain mockFilterChain;

    private static final String HEADER_KEY_AUTH = "Authorization";
    private static final String HEADER_AUTH_VALUE = "Bearer token";
    private static final String CONTEXT_PATH = "test-context-path";
    private static final String CONTEXT_PATH_WITH_LEADING_SLASH = "/" + CONTEXT_PATH;
    private static final String SERVLET_PATH = "/test-servlet-path";
    private static final String REQUEST_BODY = "{\"data\":\"test\"}";
    private static final String RESPONSE_BODY = "{\"result\":\"success\"}";
    private static final String REQUEST_URI = "/api/v1/resource/123";
    private static final String REQUEST_METHOD = "POST";
    private static final int RESPONSE_STATUS = 201;

    private final AuditPayload mockRequestAuditNode = mock(AuditPayload.class);
    private final AuditPayload mockResponseAuditNode = mock(AuditPayload.class);


    @BeforeEach
    void setUp() throws IOException, ServletException {
        // Mock dependencies
        mockAuditService = mock(AuditService.class);
        mockAuditPayloadGenerationService = mock(AuditPayloadGenerationService.class);
        final PathParameterService mockPathParameterService = mock(PathParameterService.class);

        // Instantiate the filter with mocks
        auditFilter = new AuditFilter(mockAuditService, mockAuditPayloadGenerationService, mockPathParameterService);

        // Setup mock servlet objects
        mockRequest = new MockHttpServletRequest(REQUEST_METHOD, REQUEST_URI);
        mockRequest.setContextPath(CONTEXT_PATH_WITH_LEADING_SLASH);
        mockRequest.setServletPath(SERVLET_PATH);
        mockRequest.setContent(REQUEST_BODY.getBytes());
        mockRequest.addHeader(HEADER_KEY_AUTH, HEADER_AUTH_VALUE);
        mockRequest.addParameter("param1", "value1");

        // The filter chain logic writes to the response wrapper
        mockResponse = new MockHttpServletResponse();
        mockFilterChain = mock(FilterChain.class);

        // Simulate the controller/next filter writing to the response
        doAnswer(invocation -> {
            final HttpServletResponse currentResponse = (HttpServletResponse) invocation.getArguments()[1];
            currentResponse.setStatus(RESPONSE_STATUS);
            currentResponse.setContentType("application/json");
            final PrintWriter writer = currentResponse.getWriter();
            writer.write(RESPONSE_BODY);
            writer.flush(); // Ensure content is buffered
            return null;
        }).when(mockFilterChain).doFilter(any(), any());

        when(mockPathParameterService.getPathParameters(any())).thenReturn(Map.of("pathparam1", "pathvalue1"));

        // 1. Mock for Request payload: generatePayload(String, String, Map, Map)
        when(mockAuditPayloadGenerationService.generatePayload(any(RequestInfo.class))).thenReturn(mockRequestAuditNode);

        // 2. Mock for Response payload: generatePayload(String, String, Map)
        when(mockAuditPayloadGenerationService.generatePayload(any(ResponseInfo.class))).thenReturn(mockResponseAuditNode);
    }

    @Test
    void shouldAuditRequestAndResponseWhenResponseBodyIsPresent() throws ServletException, IOException {
        assertDoesNotThrow(() -> {
            auditFilter.doFilterInternal(mockRequest, mockResponse, mockFilterChain);
        });

        verify(mockFilterChain).doFilter(any(AuditServletRequestWrapper.class), any(ContentCachingResponseWrapper.class));
        // CRITICAL: Verify that the buffered content was copied back to the real response
        assertEquals(RESPONSE_STATUS, mockResponse.getStatus());
        assertEquals(RESPONSE_BODY, mockResponse.getContentAsString());

        final ArgumentCaptor<RequestInfo> requestInfoCaptor = ArgumentCaptor.forClass(RequestInfo.class);
        final ArgumentCaptor<ResponseInfo> responseInfoCaptor = ArgumentCaptor.forClass(ResponseInfo.class);

        verify(mockAuditService).postMessageToArtemis(mockRequestAuditNode);
        verify(mockAuditService).postMessageToArtemis(mockResponseAuditNode);

        verify(mockAuditPayloadGenerationService).generatePayload(requestInfoCaptor.capture());
        // assert first invocation for request
        assertEquals(CONTEXT_PATH, requestInfoCaptor.getAllValues().getFirst().contextPath());
        assertEquals(REQUEST_BODY, requestInfoCaptor.getAllValues().getFirst().payloadBody());

        assertEquals(1, requestInfoCaptor.getAllValues().getFirst().headers().size());
        assertEquals(HEADER_AUTH_VALUE, requestInfoCaptor.getAllValues().getFirst().headers().get(HEADER_KEY_AUTH));

        assertEquals(1, requestInfoCaptor.getAllValues().getFirst().queryParams().size());
        assertEquals("value1", requestInfoCaptor.getAllValues().getFirst().queryParams().get("param1"));

        assertEquals(1, requestInfoCaptor.getAllValues().getFirst().pathParams().size());
        assertEquals("pathvalue1", requestInfoCaptor.getAllValues().getFirst().pathParams().get("pathparam1"));

        // assert second invocation for response
        verify(mockAuditPayloadGenerationService).generatePayload(responseInfoCaptor.capture());
        assertEquals(CONTEXT_PATH, responseInfoCaptor.getAllValues().getFirst().contextPath());
        assertEquals(RESPONSE_BODY, responseInfoCaptor.getAllValues().getFirst().payloadBody());
        assertEquals(1, responseInfoCaptor.getAllValues().getFirst().headers().size());
        assertEquals(HEADER_AUTH_VALUE, responseInfoCaptor.getAllValues().getFirst().headers().get(HEADER_KEY_AUTH));
    }

    @Test
    void shouldOnlyAuditRequestWhenResponseBodyIsEmpty() throws ServletException, IOException {
        // Arrange: Override the chain behavior to write nothing (empty response body)
        doAnswer(invocation -> {
            final HttpServletResponse currentResponse = (HttpServletResponse) invocation.getArguments()[1];
            currentResponse.setStatus(200);
            // Only status is set with no content returned
            return null;
        }).when(mockFilterChain).doFilter(any(), any());

        assertDoesNotThrow(() -> {
            auditFilter.doFilterInternal(mockRequest, mockResponse, mockFilterChain);
        });

        // Verify that the AuditService was called only once (for the request)
        verify(mockAuditService).postMessageToArtemis(mockRequestAuditNode);

        // Verify that the payload generation happened only once (for the request)
        final ArgumentCaptor<RequestInfo> requestInfoCaptor = ArgumentCaptor.forClass(RequestInfo.class);
        verify(mockAuditPayloadGenerationService).generatePayload(requestInfoCaptor.capture());

        // assert first invocation for request
        assertEquals(CONTEXT_PATH, requestInfoCaptor.getAllValues().getFirst().contextPath());
        assertEquals(REQUEST_BODY, requestInfoCaptor.getAllValues().getFirst().payloadBody());

        assertEquals(1, requestInfoCaptor.getAllValues().getFirst().headers().size());
        assertEquals(HEADER_AUTH_VALUE, requestInfoCaptor.getAllValues().getFirst().headers().get(HEADER_KEY_AUTH));

        assertEquals(1, requestInfoCaptor.getAllValues().getFirst().queryParams().size());
        assertEquals("value1", requestInfoCaptor.getAllValues().getFirst().queryParams().get("param1"));

        assertEquals(1, requestInfoCaptor.getAllValues().getFirst().pathParams().size());
        assertEquals("pathvalue1", requestInfoCaptor.getAllValues().getFirst().pathParams().get("pathparam1"));

        assertEquals(200, mockResponse.getStatus());
        assertEquals("", mockResponse.getContentAsString());
    }

    @Test
    void shouldNotFilterReturnsTrueForExcludedPaths() {
        final MockHttpServletRequest healthRequest = new MockHttpServletRequest("GET", "/health");
        final MockHttpServletRequest actuatorRequest = new MockHttpServletRequest("GET", "/actuator/metrics");

        assertTrue(auditFilter.shouldNotFilter(healthRequest));
        assertTrue(auditFilter.shouldNotFilter(actuatorRequest));
    }

    @Test
    void shouldNotFilterReturnsFalseForAuditedPaths() {
        final MockHttpServletRequest apiRequest = new MockHttpServletRequest("POST", "/api/data");

        assertFalse(auditFilter.shouldNotFilter(apiRequest));
    }

    /**
     * Helper method to safely create a type-specific ArgumentCaptor for Map<String, String>. This
     * is the recommended way to handle generic capture with Mockito's type erasure issues.
     */
    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> argumentCaptorForMapStringString() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
