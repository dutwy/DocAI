package com.javaee.aiservice.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;

/** Agent events are exposed only through the authenticated user's queue. */
public class AgentWebSocketAuthorizationInterceptor implements ChannelInterceptor {

    private static final String USER_AGENT_QUEUE = "/user/queue/agent";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            if (accessor.getUser() == null || !USER_AGENT_QUEUE.equals(accessor.getDestination())) {
                throw new AccessDeniedException("Agent subscription destination is not allowed");
            }
        }
        return message;
    }
}
