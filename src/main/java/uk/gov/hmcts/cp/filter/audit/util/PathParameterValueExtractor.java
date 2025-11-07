package uk.gov.hmcts.cp.filter.audit.util;

import static java.util.regex.Pattern.compile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.springframework.stereotype.Service;

@Service
public class PathParameterValueExtractor {

    public Map<String, String> extractPathParameters(final String path, final String regex, final List<String> parameterNames) {
        if (path == null) {
            return Map.of();
        }

        final Matcher matcher = compile(regex).matcher(path);
        if (!matcher.matches()) {
            return Map.of();
        }

        final Map<String, String> parameters = new HashMap<>();
        int index = 1;
        for (final String name : parameterNames) {
            parameters.put(name, matcher.group(index++));
        }
        return parameters;
    }
}
