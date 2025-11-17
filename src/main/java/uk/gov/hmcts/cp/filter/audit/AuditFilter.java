package uk.gov.hmcts.cp.filter.audit;

import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.model.RequestInfo;
import uk.gov.hmcts.cp.filter.audit.model.ResponseInfo;
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.filter.audit.service.AuditService;
import uk.gov.hmcts.cp.filter.audit.service.PathParameterService;
import uk.gov.hmcts.cp.filter.audit.wrapper.AuditServletRequestWrapper;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AuditFilter extends OncePerRequestFilter {

    private static final int CACHE_LIMIT = 65_536; // 64 KB

    private final AuditService auditService;
    private final AuditPayloadGenerationService auditPayloadGenerationService;
    private final PathParameterService pathParameterService;

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        final String path = request.getRequestURI();
        return path.contains("/health") || path.contains("/actuator");
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {

        AuditServletRequestWrapper requestWrapper = new AuditServletRequestWrapper(request);

        // Need this wrapper class tobe able to read and process request body before calling filterChain.doFilter method
        final ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        final RequestInfo requestInfo = extractRequestInfo(requestWrapper);
        final String requestBody = requestWrapper.getRequestBody();
        performRequestAudit(requestInfo);

        requestWrapper.setRequestBody(requestBody);

        filterChain.doFilter(requestWrapper, wrappedResponse);

        final String responsePayload = getPayload(wrappedResponse.getContentAsByteArray(), wrappedResponse.getCharacterEncoding());
        if (StringUtils.hasText(responsePayload)) {
            final ResponseInfo responseInfo = new ResponseInfo(requestInfo.contextPath(), requestInfo.headers(), responsePayload);
            performResponseAudit(responseInfo);
        }

        wrappedResponse.copyBodyToResponse();
    }

    private void performRequestAudit(final RequestInfo requestInfo) {
        final AuditPayload auditRequestPayload = auditPayloadGenerationService.generatePayload(requestInfo);
        auditService.postMessageToArtemis(auditRequestPayload);
    }

    private void performResponseAudit(final ResponseInfo responseInfo) {
        final AuditPayload auditRequestPayload = auditPayloadGenerationService.generatePayload(responseInfo);
        auditService.postMessageToArtemis(auditRequestPayload);
    }

    private String getPayload(final byte[] content, final String encoding) {
        try {
            return new String(content, encoding);
        } catch (IOException ex) {
            log.error("Unable to parse payload for audit", ex);
            return "";
        }
    }

    private Map<String, String> getHeaders(final HttpServletRequest request) {
        final Map<String, String> headers = new HashMap<>();
        final Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        return headers;
    }

    private Map<String, String> getQueryParams(final HttpServletRequest request) {
        final Map<String, String> queryParams = new HashMap<>();
        request.getParameterMap().forEach((key, value) -> queryParams.put(key, String.join(",", value)));
        return queryParams;
    }


    private String removeLeadingForwardSlash(final String contextPath) {
        if (contextPath != null && contextPath.startsWith("/")) {
            return contextPath.substring(1);
        }
        return contextPath;
    }

    private RequestInfo extractRequestInfo(final AuditServletRequestWrapper requestWrapper) {
        final String contextPath = removeLeadingForwardSlash(requestWrapper.getContextPath());
        final String requestPath = requestWrapper.getServletPath();
        final Map<String, String> headers = getHeaders(requestWrapper);
        final Map<String, String> queryParams = getQueryParams(requestWrapper);
        final Map<String, String> pathParams = pathParameterService.getPathParameters(requestPath);

        return new RequestInfo(
                contextPath,
                headers,
                queryParams,
                pathParams,
                requestWrapper.getRequestBody()
        );
    }

}