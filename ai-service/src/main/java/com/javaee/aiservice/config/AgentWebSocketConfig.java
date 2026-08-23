package com.javaee.aiservice.config;

import com.javaee.aiservice.security.AgentWebSocketAuthorizationInterceptor;
import com.javaee.common.config.security.JwtStompAuthenticationInterceptor;
import com.javaee.common.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Agent 工作台实时进度通道。
 * 前端可连接 /ws/agent，并订阅 /topic/agent/** 获取任务和索引事件。
 */
@Configuration
@EnableWebSocketMessageBroker
public class AgentWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtStompAuthenticationInterceptor authenticationInterceptor;
    private final AgentWebSocketAuthorizationInterceptor authorizationInterceptor =
            new AgentWebSocketAuthorizationInterceptor();
    private final String[] allowedOriginPatterns;

    public AgentWebSocketConfig(
            JwtUtils jwtUtils,
            @Value("${security.websocket.allowed-origin-patterns:http://localhost:5173,http://localhost:8080}")
            String allowedOriginPatterns) {
        this.authenticationInterceptor = new JwtStompAuthenticationInterceptor(jwtUtils);
        this.allowedOriginPatterns = java.util.Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty() && !"*".equals(origin))
                .toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/agent")
                .setAllowedOriginPatterns(allowedOriginPatterns)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor, authorizationInterceptor);
    }
}
