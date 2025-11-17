package uk.gov.hmcts.cp.filter.audit.model;

import java.util.Map;

public record RequestInfo(
        String contextPath,
        Map<String, String> headers,
        Map<String, String> queryParams,
        Map<String, String> pathParams,
        String payloadBody
) {
}