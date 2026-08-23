package com.javaee.gateway.filter;

import com.javaee.common.observability.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RequestCorrelationGlobalFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_ATTRIBUTE = RequestCorrelationGlobalFilter.class.getName() + ".traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceContext.resolveTraceId(
                exchange.getRequest().getHeaders().getFirst(TraceContext.TRACE_HEADER));
        long startedAt = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(TraceContext.TRACE_HEADER, traceId))
                .build();
        ServerWebExchange correlated = exchange.mutate().request(request).build();
        correlated.getAttributes().put(TRACE_ID_ATTRIBUTE, traceId);
        correlated.getResponse().getHeaders().set(TraceContext.TRACE_HEADER, traceId);

        return chain.filter(correlated).doFinally(signal -> {
            MDC.put(TraceContext.TRACE_ID, traceId);
            try {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                log.info("gateway_request method={} path={} status={} durationMs={}",
                        request.getMethod(), request.getPath().value(),
                        correlated.getResponse().getStatusCode(), durationMs);
            } finally {
                MDC.remove(TraceContext.TRACE_ID);
            }
        });
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
