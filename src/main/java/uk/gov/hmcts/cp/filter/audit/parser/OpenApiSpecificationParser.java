package uk.gov.hmcts.cp.filter.audit.parser;

import uk.gov.hmcts.cp.filter.audit.util.ClasspathResourceLoader;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Getter
@Component
public class OpenApiSpecificationParser implements RestApiParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiSpecificationParser.class);

    private final Map<String, Pattern> pathPatterns = new HashMap<>();

    private final ClasspathResourceLoader resourceLoader;

    private final OpenAPIParser openAPIParser;

    @Value("${audit.http.openapi-rest-spec}")
    private final String restSpecification;

    @Value("${audit.http.enabled}")
    private boolean isHttpAuditEnabled;

    public OpenApiSpecificationParser(final ClasspathResourceLoader resourceLoader,
                                      @Value("${audit.http.openapi-rest-spec}") final String restSpecification,
                                      final OpenAPIParser openAPIParser,
                                      @Value("${audit.http.enabled}") final boolean isHttpAuditEnabled) {
        this.resourceLoader = resourceLoader;
        this.restSpecification = restSpecification;
        this.openAPIParser = openAPIParser;
        this.isHttpAuditEnabled = isHttpAuditEnabled;
    }

    @PostConstruct
    public void init() {

        if (!isHttpAuditEnabled) {
            LOGGER.info("HTTP Audit is disabled; skipping OpenAPI specification parsing.");
            return;
        }

        final Optional<Resource> optionalResource = resourceLoader.loadFilesByPattern(restSpecification);

        if (optionalResource.isEmpty()) {
            LOGGER.warn("No OpenAPI specification found at the specified path: {}", restSpecification);
            throw new IllegalArgumentException("No OpenAPI specification found at the specified path");
        }

        final OpenAPI openAPI;
        try {
            final String specificationUrl = optionalResource.get().getURL().toString();
            openAPI = openAPIParser.readLocation(specificationUrl, null, null).getOpenAPI();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to parse OpenAPI specification at location", e);
        }

        final Paths paths = openAPI.getPaths();// NOPMD UseInterfaceType
        if (null == paths || paths.isEmpty()) {
            LOGGER.warn("Supplied specification has no endpoints defined: {}", restSpecification);
            throw new IllegalArgumentException("Supplied specification has no endpoints defined: " + restSpecification);
        }

        LOGGER.info("Loaded {} paths from OpenAPI specification", paths.size());

        paths.forEach((path, pathItem) -> {
            if (null == pathItem || null == path) {
                throw new IllegalArgumentException("Invalid path specifications in file : " + restSpecification);
            }

            final boolean hasPathParamsAtPathLevel = pathItem.getParameters() != null && pathItem.getParameters().stream()
                    .anyMatch(param -> "path".equalsIgnoreCase(param.getIn()));

            final boolean hasPathParamsAtMethodLevel = hasPathParamsAtMethodLevel(pathItem);

            if (hasPathParamsAtPathLevel || hasPathParamsAtMethodLevel) {
                final String regexPath = path.replaceAll("\\{[^/]+}", "([^/]+)");
                pathPatterns.put(path, Pattern.compile(regexPath));
            }
        });
    }

    public boolean hasPathParamsAtMethodLevel(PathItem pathItem) {

        // 1. Iterate over all defined operations (GET, POST, PUT, etc.)
        for (Operation operation : pathItem.readOperations()) {

            // 2. Check if the operation has parameters defined
            if (CollectionUtils.isEmpty(operation.getParameters())) {
                continue;
            }

            // 3. Check for path parameters within the current operation
            final boolean hasPathParamsAtMethodLevel = operation.getParameters().stream()
                    .anyMatch(param -> "path".equalsIgnoreCase(param.getIn()));

            if (hasPathParamsAtMethodLevel) {
                return true;
            }

        }
        return false;
    }

}