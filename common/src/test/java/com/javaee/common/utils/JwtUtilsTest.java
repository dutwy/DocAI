package com.javaee.common.utils;

import com.javaee.common.config.security.JwtStompAuthenticationInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JwtUtilsTest {

    private final JwtUtils jwtUtils = new JwtUtils(
            "test-only-secret-that-is-at-least-thirty-two-bytes",
            "doc-ai-test",
            60_000,
            120_000);

    @Test
    void accessAndRefreshTokensAreNotInterchangeable() {
        String accessToken = jwtUtils.generateToken(7L, "alice", "USER");
        String refreshToken = jwtUtils.generateRefreshToken(7L);

        assertTrue(jwtUtils.validateAccessToken(accessToken));
        assertFalse(jwtUtils.validateRefreshToken(accessToken));
        assertTrue(jwtUtils.validateRefreshToken(refreshToken));
        assertFalse(jwtUtils.validateAccessToken(refreshToken));
        assertThrows(RuntimeException.class, () -> jwtUtils.getUserId(refreshToken));
    }

    @Test
    void rejectsWeakSecrets() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtils("too-short", "doc-ai-test", 60_000, 120_000));
    }

    @Test
    void acceptsPreviousKeyOnlyDuringRotationWindow() {
        JwtUtils oldSigner = new JwtUtils(
                "old-test-secret-that-is-at-least-thirty-two-bytes",
                "doc-ai-test", 60_000, 120_000);
        String oldToken = oldSigner.generateToken(3L, "legacy", "USER");
        JwtUtils rotatedVerifier = new JwtUtils(
                "new-test-secret-that-is-at-least-thirty-two-bytes",
                "old-test-secret-that-is-at-least-thirty-two-bytes",
                "doc-ai-test", 60_000, 120_000);

        assertTrue(rotatedVerifier.validateAccessToken(oldToken));
    }

    @Test
    void stompConnectRequiresAnAccessToken() {
        JwtStompAuthenticationInterceptor interceptor = new JwtStompAuthenticationInterceptor(jwtUtils);
        String accessToken = jwtUtils.generateToken(9L, "bob", "USER");
        Message<byte[]> authenticatedConnect = connectMessage("Bearer " + accessToken);

        interceptor.preSend(authenticatedConnect, null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(authenticatedConnect);
        assertTrue(accessor.getUser() != null && "9".equals(accessor.getUser().getName()));

        String refreshToken = jwtUtils.generateRefreshToken(9L);
        assertThrows(RuntimeException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + refreshToken), null));
    }

    private Message<byte[]> connectMessage(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
