package uk.gov.hmcts.cp.filter.audit.wrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.mock.web.DelegatingServletInputStream;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class AuditServletRequestWrapperTest {

    private HttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        mockRequest = Mockito.mock(HttpServletRequest.class);
    }

    @Test
    void setRequestBodyShouldUpdateRequestBody() throws IOException {
        String initialBody = "original body";
        String updatedBody = "updated body";

        when(mockRequest.getInputStream()).thenReturn(
            new DelegatingServletInputStream(new ByteArrayInputStream(initialBody.getBytes(StandardCharsets.UTF_8)))
        );

        AuditServletRequestWrapper wrapper = new AuditServletRequestWrapper(mockRequest);
        assertEquals(initialBody, wrapper.getRequestBody());

        wrapper.setRequestBody(updatedBody);
        assertEquals(updatedBody, wrapper.getRequestBody());

        wrapper.setRequestBody("");
        assertEquals("", wrapper.getRequestBody());
    }
}
