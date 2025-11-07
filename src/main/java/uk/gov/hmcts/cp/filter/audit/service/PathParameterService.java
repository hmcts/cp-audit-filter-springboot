package uk.gov.hmcts.cp.filter.audit.service;

import java.util.Map;

@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PathParameterService {
    /**
     * Extracts path parameters from the given servlet path.
     *
     * @param servletPath the servlet path to extract parameters from
     * @return a map of path parameter names to their corresponding values
     */
    Map<String, String> getPathParameters(String servletPath);
}
