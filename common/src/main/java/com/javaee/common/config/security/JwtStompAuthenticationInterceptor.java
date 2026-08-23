package com.javaee.common.config.security;

import com.javaee.common.constant.CommonConstant;
import com.javaee.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/** Authenticates STOMP CONNECT frames with an access token. */
public class JwtStompAuthenticationInterceptor implements ChannelInterceptor {

    private final JwtUtils jwtUtils;

    public JwtStompAuthenticationInterceptor(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(command)) {
            String authHeader = accessor.getFirstNativeHeader(CommonConstant.TOKEN_HEADER);
            try {
                String token = jwtUtils.extractToken(authHeader);
                Claims claims = jwtUtils.parseAccessToken(token);
                Long userId = claims.get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
                if (userId == null) {
                    throw new AccessDeniedException("JWT does not contain a user id");
                }
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                String role = claims.get(CommonConstant.TOKEN_CLAIM_ROLE, String.class);
                if (role != null && !role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase()));
                }
                accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, authorities));
            } catch (RuntimeException e) {
                throw new AccessDeniedException("WebSocket authentication failed", e);
            }
        } else if ((StompCommand.SEND.equals(command) || StompCommand.SUBSCRIBE.equals(command))
                && accessor.getUser() == null) {
            throw new AccessDeniedException("WebSocket authentication is required");
        }
        return message;
    }
}
