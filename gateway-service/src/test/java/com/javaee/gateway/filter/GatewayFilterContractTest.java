package com.javaee.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.observability.TraceContext;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayFilterContractTest {

    @Test
    void correlationFilterPropagatesSafeRequestId() {
        String requestId = "client-request-1234";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/files/list")
                        .header(TraceContext.TRACE_HEADER, requestId).build());
        AtomicReference<String> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = current -> {
            forwarded.set(current.getRequest().getHeaders().getFirst(TraceContext.TRACE_HEADER));
            return Mono.empty();
        };

        new RequestCorrelationGlobalFilter().filter(exchange, chain).block();

        assertEquals(requestId, forwarded.get());
        assertEquals(requestId, exchange.getResponse().getHeaders().getFirst(TraceContext.TRACE_HEADER));
    }

    @Test
    void correlationFilterReplacesUnsafeRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/files/list")
                        .header(TraceContext.TRACE_HEADER, "bad header value").build());
        AtomicReference<String> forwarded = new AtomicReference<>();

        new RequestCorrelationGlobalFilter().filter(exchange, current -> {
            forwarded.set(current.getRequest().getHeaders().getFirst(TraceContext.TRACE_HEADER));
            return Mono.empty();
        }).block();

        assertNotNull(forwarded.get());
        assertTrue(forwarded.get().matches("[a-f0-9]{32}"));
    }

    @Test
    void errorWriterUsesUnifiedJsonContract() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/private").build());
        exchange.getAttributes().put(RequestCorrelationGlobalFilter.TRACE_ID_ATTRIBUTE, "gateway-trace-1234");

        new GatewayErrorResponseWriter(new ObjectMapper())
                .write(exchange, ErrorCodeEnum.UNAUTHORIZED, "需要登录").block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        String json = exchange.getResponse().getBodyAsString().block();
        assertNotNull(json);
        assertEquals(401, new ObjectMapper().readTree(json).get("code").asInt());
        assertEquals("gateway-trace-1234", new ObjectMapper().readTree(json).get("traceId").asText());
    }
}
