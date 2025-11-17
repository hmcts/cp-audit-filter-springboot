package uk.gov.hmcts.cp.filter.audit.model;

import java.util.Map;

public record ResponseInfo(
        String contextPath,
        Map<String, String> headers,
        String payloadBody
) {
}