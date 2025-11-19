package uk.gov.hmcts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser;

@SpringBootApplication
@ComponentScan(
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = OpenApiSpecificationParser.class
        )
)
@Slf4j
public class ExampleApplication {
    public static void main(final String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
