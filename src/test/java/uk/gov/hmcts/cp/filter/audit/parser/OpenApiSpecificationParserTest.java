package uk.gov.hmcts.cp.filter.audit.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.filter.audit.util.ClasspathResourceLoader;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

class OpenApiSpecificationParserTest {

    private static final String CLASSPATH_OPENAPI_YAML = "classpath:/openapi.yaml";
    private static final String FILE_DUMMY_PATH = "file:/dummy/path";
    private static final String API_RESOURCE_PATH = "/api/resource/{id}";
    private static final String API_PATH = "path";

    @Test
    @DisplayName("Throws exception when OpenAPI specification path is null")
    void throwsExceptionWhenOpenApiSpecPathIsNull() {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, null, openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No OpenAPI specification found at the specified path");
    }

    @Test
    @DisplayName("Throws exception when OpenAPI specification resource is missing")
    void throwsExceptionWhenOpenApiSpecResourceIsMissing() {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.empty());
        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No OpenAPI specification found at the specified path");
    }

    @Test
    @DisplayName("Throws exception when OpenAPI specification cannot be read")
    void throwsExceptionWhenOpenApiSpecCannotBeRead() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenThrow(new IOException("IO error"));
        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to parse OpenAPI specification at location");
    }

    @Test
    @DisplayName("Throws exception when OpenAPI specification has no paths")
    void throwsExceptionWhenOpenApiSpecHasNoPaths() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL(FILE_DUMMY_PATH));

        final OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(null);

        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        final SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supplied specification has no endpoints defined");
    }

    @Test
    @DisplayName("Adds path patterns for multiple valid paths")
    void addsPathPatternsForMultipleValidPaths() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL(FILE_DUMMY_PATH));

        final Parameter pathParam = new Parameter().in(API_PATH).name("id");
        final PathItem pathItem1 = new PathItem().parameters(List.of(pathParam));
        final PathItem pathItem2 = new PathItem().parameters(List.of(pathParam));

        @SuppressWarnings("PMD.UseInterfaceInsteadOfImplementation")
        final Paths paths = new Paths();// NOPMD UseInterfaceType

        paths.addPathItem(API_RESOURCE_PATH, pathItem1);
        paths.addPathItem("/api/other-resource/{id}", pathItem2);

        final OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        final SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);
        parser.init();

        final Map<String, Pattern> patterns = parser.getPathPatterns();
        assertThat(patterns).containsKey(API_RESOURCE_PATH);
        assertThat(patterns).containsKey("/api/other-resource/{id}");
        assertThat(patterns.get(API_RESOURCE_PATH).pattern()).isEqualTo("/api/resource/([^/]+)");
        assertThat(patterns.get("/api/other-resource/{id}").pattern()).isEqualTo("/api/other-resource/([^/]+)");
    }

    @Test
    @DisplayName("Does not add path pattern if path has no path parameters")
    void doesNotAddPathPatternIfNoPathParameters() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL(FILE_DUMMY_PATH));

        final Parameter queryParam = new Parameter().in("query").name("q");
        final PathItem pathItem = new PathItem().parameters(List.of(queryParam));
        final Paths paths = new Paths();// NOPMD UseInterfaceType
        paths.addPathItem("/api/resource", pathItem);

        final OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        final SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);
        parser.init();

        assertThat(parser.getPathPatterns()).isEmpty();
    }

    @Test
    @DisplayName("Does not add path pattern if path parameters are null")
    void doesNotAddPathPatternIfPathParametersAreNull() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL(FILE_DUMMY_PATH));

        final PathItem pathItem = new PathItem().parameters(null);
        final Paths paths = new Paths();// NOPMD UseInterfaceType
        paths.addPathItem(API_RESOURCE_PATH, pathItem);

        final OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        final SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);
        parser.init();

        assertThat(parser.getPathPatterns()).isEmpty();
    }

    @Test
    @DisplayName("Adds multiple path patterns for paths with multiple path parameters")
    void addsMultiplePathPatternsForPathsWithMultiplePathParameters() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL(FILE_DUMMY_PATH));

        final Parameter pathParam1 = new Parameter().in(API_PATH).name("id");
        final Parameter pathParam2 = new Parameter().in(API_PATH).name("subId");
        final PathItem pathItem = new PathItem().parameters(List.of(pathParam1, pathParam2));
        final Paths paths = new Paths();// NOPMD UseInterfaceType
        paths.addPathItem("/api/resource/{id}/sub-resource/{subId}", pathItem);

        final OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        final SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);
        parser.init();

        final Map<String, Pattern> patterns = parser.getPathPatterns();
        assertThat(patterns).containsKey("/api/resource/{id}/sub-resource/{subId}");
        assertThat(patterns.get("/api/resource/{id}/sub-resource/{subId}").pattern()).isEqualTo("/api/resource/([^/]+)/sub-resource/([^/]+)");
    }

    @Test
    @DisplayName("Throws exception if OpenAPI specification contains invalid paths")
    void doesNotAddPathPatternIfOpenApiSpecContainsInvalidPaths() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL(FILE_DUMMY_PATH));

        final Paths paths = new Paths();// NOPMD UseInterfaceType
        paths.addPathItem(API_RESOURCE_PATH, null);

        final OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        final SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "openapi.yaml", openAPIParser);
        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid path specifications in file : " + "openapi.yaml");
    }

    @Test
    @DisplayName("Adds path pattern for paths with mixed parameter types")
    void addsPathPatternForPathsWithMixedParameterTypes() throws Exception {
        final ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        final Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL(FILE_DUMMY_PATH));

        final Parameter pathParam = new Parameter().in("path").name("id");
        final Parameter queryParam = new Parameter().in("query").name("q");
        final PathItem pathItem = new PathItem().parameters(List.of(pathParam, queryParam));
        final Paths paths = new Paths();// NOPMD UseInterfaceType
        paths.addPathItem(API_RESOURCE_PATH, pathItem);

        final OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        final OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        final SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        final OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, CLASSPATH_OPENAPI_YAML, openAPIParser);
        parser.init();

        final Map<String, Pattern> patterns = parser.getPathPatterns();
        assertThat(patterns).containsKey(API_RESOURCE_PATH);
        assertThat(patterns.get(API_RESOURCE_PATH).pattern()).isEqualTo("/api/resource/([^/]+)");
    }
}

