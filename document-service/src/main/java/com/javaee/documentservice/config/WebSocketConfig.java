package com.javaee.documentservice.config;

import com.javaee.common.config.security.JwtStompAuthenticationInterceptor;
import com.javaee.common.utils.JwtUtils;
import com.javaee.documentservice.security.DocumentWebSocketAuthorizationInterceptor;
import com.javaee.documentservice.service.DocumentAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtStompAuthenticationInterceptor authenticationInterceptor;
    private final DocumentWebSocketAuthorizationInterceptor authorizationInterceptor;
    private final String[] allowedOriginPatterns;

    public WebSocketConfig(
            JwtUtils jwtUtils,
            DocumentAccessService documentAccessService,
            @Value("${security.websocket.allowed-origin-patterns:http://localhost:5173,http://localhost:8080}")
            String allowedOriginPatterns) {
        this.authenticationInterceptor = new JwtStompAuthenticationInterceptor(jwtUtils);
        this.authorizationInterceptor = new DocumentWebSocketAuthorizationInterceptor(documentAccessService);
        this.allowedOriginPatterns = splitAllowedOrigins(allowedOriginPatterns);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/collaborate")
                .setAllowedOriginPatterns(allowedOriginPatterns)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor, authorizationInterceptor);
    }

    private String[] splitAllowedOrigins(String configuredOrigins) {
        return java.util.Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty() && !"*".equals(origin))
                .toArray(String[]::new);
    }
}
