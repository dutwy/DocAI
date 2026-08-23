package com.javaee.gateway.filter;

import com.javaee.common.utils.JwtUtils;
import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.gateway.config.RabbitMQConfig;
import com.javaee.gateway.util.RabbitMQUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * JWT鉴权全局过滤器
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private RabbitMQUtil rabbitMQUtil;

    private final JwtUtils jwtUtils;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public AuthGlobalFilter(JwtUtils jwtUtils, GatewayErrorResponseWriter errorResponseWriter) {
        this.jwtUtils = jwtUtils;
        this.errorResponseWriter = errorResponseWriter;
    }

    private static final Set<String> PUBLIC_POST_PATHS = Set.of(
            "/api/users/login",
            "/api/users/register",
            "/api/users/refresh"
    );

    private static final Set<String> TRUSTED_IDENTITY_HEADERS = Set.of(
            "X-User-Id", "X-Username", "X-Role", "X-Permission-Groups"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = stripUntrustedIdentityHeaders(exchange.getRequest());
        exchange = exchange.mutate().request(request).build();
        String path = request.getPath().value();
        String method = request.getMethod() == null ? "UNKNOWN" : request.getMethod().name();

        log.info("网关收到请求: {} {}", method, path);

        // 发送网关日志消息
        sendGatewayLog(path, method, null);

        // 检查是否在白名单中
        if (isWhiteList(path, method)) {
            return chain.filter(exchange);
        }

        // 从请求头中获取令牌
        HttpHeaders headers = request.getHeaders();
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("请求未授权: {} {}", method, path);
            sendGatewayAlert("UNAUTHORIZED", "缺少有效的认证令牌: " + path);
            return unauthorized(exchange);
        }

        // 提取令牌
        String token = authHeader.substring(7);

        // 验证令牌
        if (!jwtUtils.validateAccessToken(token)) {
            log.warn("令牌无效: {} {}", method, path);
            sendGatewayAlert("INVALID_TOKEN", "无效的访问令牌: " + path);
            return unauthorized(exchange);
        }

        // 令牌有效，将用户信息存储到请求头中
        try {
            Long userId = jwtUtils.getUserId(token);
            String username = jwtUtils.getUsername(token);
            String role = jwtUtils.getRole(token);

            // 创建新的请求头
            ServerHttpRequest.Builder requestBuilder = request.mutate().headers(identityHeaders -> {
                TRUSTED_IDENTITY_HEADERS.forEach(identityHeaders::remove);
                identityHeaders.set("X-User-Id", userId.toString());
                if (username != null && !username.isBlank()) {
                    identityHeaders.set("X-Username", username);
                }
                if (role != null && !role.isBlank()) {
                    identityHeaders.set("X-Role", role);
                }
            });

            // 更新网关日志，添加用户ID
            sendGatewayLog(path, method, userId.toString());

            return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
        } catch (Exception e) {
            log.error("处理令牌时出错", e);
            sendGatewayAlert("TOKEN_ERROR", "处理访问令牌时出错: " + e.getMessage());
            return unauthorized(exchange);
        }
    }

    /**
     * 发送网关日志消息
     */
    private void sendGatewayLog(String path, String method, String userId) {
        Map<String, Object> message = new HashMap<>();
        message.put("path", path);
        message.put("method", method);
        message.put("userId", userId);
        message.put("timestamp", LocalDateTime.now().toString());
        
        try {
            log.info("发送网关日志消息到 RabbitMQ");
            rabbitMQUtil.send(RabbitMQConfig.GATEWAY_EXCHANGE, RabbitMQConfig.GATEWAY_LOG_ROUTING_KEY, message);
        } catch (Exception e) {
            log.warn("网关日志消息发送 RabbitMQ 失败，不影响当前请求: path={}, method={}, error={}",
                    path, method, e.getMessage(), e);
        }
    }

    /**
     * 发送网关告警消息
     */
    private void sendGatewayAlert(String alertType, String description) {
        Map<String, Object> message = new HashMap<>();
        message.put("alertType", alertType);
        message.put("description", description);
        message.put("timestamp", LocalDateTime.now().toString());
        
        try {
            log.info("发送网关告警消息到 RabbitMQ");
            rabbitMQUtil.send(RabbitMQConfig.GATEWAY_EXCHANGE, RabbitMQConfig.GATEWAY_ALERT_ROUTING_KEY, message);
        } catch (Exception e) {
            log.warn("网关告警消息发送 RabbitMQ 失败，不影响当前请求: alertType={}, error={}",
                    alertType, e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        // 过滤器执行顺序，数字越小优先级越高
        return -100;
    }

    /**
     * 检查路径是否在白名单中
     */
    private boolean isWhiteList(String path, String method) {
        if ("OPTIONS".equals(method)) {
            return true;
        }
        if ("POST".equals(method) && PUBLIC_POST_PATHS.contains(path)) {
            return true;
        }
        if ("GET".equals(method) && (isEndpointOrChild(path, "/actuator/health")
                || path.equals("/actuator/prometheus"))) {
            return true;
        }
        // SockJS handshakes cannot reliably carry an HTTP Authorization header.
        // Authentication is enforced on the subsequent STOMP CONNECT frame.
        return isEndpointOrChild(path, "/ws/agent")
                || isEndpointOrChild(path, "/ws/collaborate");
    }

    private boolean isEndpointOrChild(String path, String endpoint) {
        return path.equals(endpoint) || path.startsWith(endpoint + "/");
    }

    private ServerHttpRequest stripUntrustedIdentityHeaders(ServerHttpRequest request) {
        return request.mutate().headers(headers ->
                TRUSTED_IDENTITY_HEADERS.forEach(headers::remove)).build();
    }

    /**
     * 返回未授权响应
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        return errorResponseWriter.write(exchange, ErrorCodeEnum.UNAUTHORIZED,
                ErrorCodeEnum.UNAUTHORIZED.getMessage());
    }

}
