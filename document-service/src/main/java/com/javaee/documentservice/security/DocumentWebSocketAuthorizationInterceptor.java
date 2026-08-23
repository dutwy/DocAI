package com.javaee.documentservice.security;

import com.javaee.documentservice.service.DocumentAccessService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;

import java.security.Principal;

/** Restricts document topic subscriptions to users with document read access. */
public class DocumentWebSocketAuthorizationInterceptor implements ChannelInterceptor {

    private static final String DOCUMENT_TOPIC_PREFIX = "/topic/doc/";
    private static final String USER_DOCUMENT_TOPIC_PREFIX = "/user/topic/doc/";

    private final DocumentAccessService documentAccessService;

    public DocumentWebSocketAuthorizationInterceptor(DocumentAccessService documentAccessService) {
        this.documentAccessService = documentAccessService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        String documentId = extractDocumentId(destination);
        Long userId = requiredUserId(accessor.getUser());
        documentAccessService.requireReadableDocument(documentId, userId);
        return message;
    }

    private String extractDocumentId(String destination) {
        if (destination == null) {
            throw new AccessDeniedException("WebSocket destination is required");
        }
        String prefix;
        if (destination.startsWith(DOCUMENT_TOPIC_PREFIX)) {
            prefix = DOCUMENT_TOPIC_PREFIX;
        } else if (destination.startsWith(USER_DOCUMENT_TOPIC_PREFIX)) {
            prefix = USER_DOCUMENT_TOPIC_PREFIX;
        } else {
            throw new AccessDeniedException("Subscription destination is not allowed");
        }
        String documentId = destination.substring(prefix.length());
        if (documentId.isBlank() || documentId.contains("/")) {
            throw new AccessDeniedException("Invalid document subscription");
        }
        return documentId;
    }

    private Long requiredUserId(Principal principal) {
        if (principal == null) {
            throw new AccessDeniedException("WebSocket authentication is required");
        }
        try {
            return Long.valueOf(principal.getName());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Invalid WebSocket principal", e);
        }
    }
}
