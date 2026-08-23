package com.javaee.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.common.config.security.SecurityErrorResponseWriter;
import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.exception.BusinessException;
import com.javaee.common.exception.GlobalExceptionHandler;
import com.javaee.common.model.Result;
import com.javaee.common.observability.RequestCorrelationFilter;
import com.javaee.common.observability.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiContractTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void resultCarriesCurrentTraceId() {
        MDC.put(TraceContext.TRACE_ID, "trace-contract-123");

        Result<String> result = Result.success("ok");

        assertThat(result.getTraceId()).isEqualTo("trace-contract-123");
    }

    @Test
    void businessExceptionUsesMatchingHttpStatus() {
        ResponseEntity<Result<Void>> response = new GlobalExceptionHandler()
                .handleBusinessException(new BusinessException(ErrorCodeEnum.CONFLICT));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(409);
    }

    @Test
    void requestFilterReusesValidRequestIdAndCleansMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.TRACE_HEADER, "request-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestCorrelationFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TraceContext.TRACE_HEADER)).isEqualTo("request-trace-123");
        assertThat(MDC.get(TraceContext.TRACE_ID)).isNull();
    }

    @Test
    void securityWriterUsesUnifiedJsonContract() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(new ObjectMapper());

        writer.write(response, ErrorCodeEnum.FORBIDDEN, "denied");

        Result<?> body = new ObjectMapper().readValue(response.getContentAsByteArray(), Result.class);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.getCode()).isEqualTo(403);
        assertThat(body.getMessage()).isEqualTo("denied");
    }
}
