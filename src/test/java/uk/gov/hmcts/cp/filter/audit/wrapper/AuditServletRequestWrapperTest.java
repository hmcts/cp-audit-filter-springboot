package uk.gov.hmcts.cp.filter.audit.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.DelegatingServletInputStream;

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
