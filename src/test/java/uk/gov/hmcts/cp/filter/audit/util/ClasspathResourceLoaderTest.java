package uk.gov.hmcts.cp.filter.audit.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ClasspathResourceLoaderTest.TestConfig.class)
class ClasspathResourceLoaderTest {

    // Inject the component we are testing
    @Autowired
    private ClasspathResourceLoader resourceLoader;

    @Test
    void shouldFindFileSuccessfully() {
        final String pattern = "-res.txt";

        final Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        assertTrue(result.isPresent(), "Resource should be found on the classpath.");

        assertTrue("test-res.txt".equalsIgnoreCase(result.get().getFilename()), "The found resource should be the expected file.");
    }

    @Test
    void shouldFindNestedFileSuccessfully() {
        final String pattern = "-test-*.txt";

        final Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        assertTrue(result.isPresent(), "Resource should be found on the classpath.");

        assertTrue("nested-test-resource.txt".equalsIgnoreCase(result.get().getFilename()), "The found resource should be the expected file.");
    }

    @Test
    void shouldFindFileFilesByFullNameSuccessfully() {
        final String pattern = "nested-test-resource.txt";

        final Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        assertTrue(result.isPresent(), "Resource should be found on the classpath.");

        assertTrue("nested-test-resource.txt".equalsIgnoreCase(result.get().getFilename()), "The found resource should be the expected file.");
    }

    /**
     * Test case 2: Fails gracefully when no file matches the pattern.
     */
    @Test
    void shouldReturnEmptyWhenNoMatch() {
        final String pattern = "nonexistent-file-123.yaml";

        final Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        assertTrue(result.isEmpty(), "Result should be empty when no resource matches the pattern.");
    }

    @Configuration
    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    public static class TestConfig {
        @Bean
        public ClasspathResourceLoader classpathResourceLoader(final ResourceLoader resourceLoader) {
            return new ClasspathResourceLoader(resourceLoader);
        }
    }
}