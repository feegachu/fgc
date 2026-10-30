package com.susukkang.fgc.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ApiMultipartExceptionResolverTest {
    private final GlobalExceptionHandler errors = mock(GlobalExceptionHandler.class);
    private final ApiMultipartExceptionResolver resolver = new ApiMultipartExceptionResolver(errors, new ObjectMapper());

    @Test void nonApiMultipartFailureKeepsExistingMpaHandling() {
        var request = new MockHttpServletRequest();
        request.setServletPath("/upload");
        assertThat(resolver.resolveException(request, new MockHttpServletResponse(), null,
                new MaxUploadSizeExceededException(32))).isNull();
        verifyNoInteractions(errors);
    }

    @Test void otherApiExceptionsKeepExistingHandling() {
        var request = new MockHttpServletRequest();
        request.setServletPath("/api/v1/intake/documents");
        assertThat(resolver.resolveException(request, new MockHttpServletResponse(), null,
                new IllegalArgumentException("test"))).isNull();
        verifyNoInteractions(errors);
    }
}
