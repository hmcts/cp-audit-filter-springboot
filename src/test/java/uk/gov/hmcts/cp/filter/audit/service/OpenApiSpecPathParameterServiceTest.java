package uk.gov.hmcts.cp.filter.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser;
import uk.gov.hmcts.cp.filter.audit.util.PathParameterNameExtractor;
import uk.gov.hmcts.cp.filter.audit.util.PathParameterValueExtractor;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiSpecPathParameterServiceTest {

    private OpenApiSpecificationParser openApiSpecificationParser;
    private PathParameterNameExtractor pathParameterNameExtractor;
    private PathParameterValueExtractor pathParameterValueExtractor;
    private OpenApiSpecPathParameterService service;

    @BeforeEach
    void setUp() {
        openApiSpecificationParser = mock(OpenApiSpecificationParser.class);
        pathParameterNameExtractor = mock(PathParameterNameExtractor.class);
        pathParameterValueExtractor = mock(PathParameterValueExtractor.class);
        service = new OpenApiSpecPathParameterService(openApiSpecificationParser, pathParameterNameExtractor, pathParameterValueExtractor);
    }

    @Test
    @DisplayName("Returns path parameters when servlet path matches an OpenAPI pattern")
    void returnsPathParametersWhenServletPathMatchesPattern() {
        final String servletPath = "/api/resource/123";
        final String apiSpecPath = "/api/resource/{id}";
        final Pattern pattern = Pattern.compile("/api/resource/\\d+");
        final Map<String, Pattern> pathPatterns = Map.of(apiSpecPath, pattern);
        final List<String> pathParameterNames = List.of("id");
        final Map<String, String> expectedPathParameters = Map.of("id", "123");

        when(openApiSpecificationParser.getPathPatterns()).thenReturn(pathPatterns);
        when(pathParameterNameExtractor.extractPathParametersFromApiSpec(apiSpecPath)).thenReturn(pathParameterNames);
        when(pathParameterValueExtractor.extractPathParameters(servletPath, pattern.pattern(), pathParameterNames))
                .thenReturn(expectedPathParameters);

        final Map<String, String> result = service.getPathParameters(servletPath);

        assertThat(result).isEqualTo(expectedPathParameters);
    }

    @Test
    @DisplayName("Returns empty map when servlet path does not match any OpenAPI pattern")
    void returnsEmptyMapWhenServletPathDoesNotMatchPattern() {
        final String servletPath = "/api/unknown";
        final Map<String, Pattern> pathPatterns = Map.of("/api/resource/{id}", Pattern.compile("/api/resource/\\d+"));

        when(openApiSpecificationParser.getPathPatterns()).thenReturn(pathPatterns);

        final Map<String, String> result = service.getPathParameters(servletPath);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty map when OpenAPI patterns are empty")
    void returnsEmptyMapWhenOpenApiPatternsAreEmpty() {
        final String servletPath = "/api/resource/123";
        final Map<String, Pattern> pathPatterns = Map.of();

        when(openApiSpecificationParser.getPathPatterns()).thenReturn(pathPatterns);

        final Map<String, String> result = service.getPathParameters(servletPath);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty map when servlet path is null")
    void returnsEmptyMapWhenServletPathIsNull() {
        final Map<String, String> result = service.getPathParameters(null);

        assertThat(result).isEmpty();
    }
}
