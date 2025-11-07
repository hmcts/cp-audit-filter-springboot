package uk.gov.hmcts.cp.filter.audit.parser;

import io.swagger.parser.OpenAPIParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiParserProducer {

    @Bean
    public OpenAPIParser openAPIParser() {
        return new OpenAPIParser();
    }
}
