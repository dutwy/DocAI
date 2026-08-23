package com.javaee.user.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.utils.JwtUtils;
import com.javaee.user.entity.User;
import com.javaee.user.service.impl.UserServiceImpl;
import com.javaee.user.util.RedisUtil;
import com.javaee.user.vo.LoginVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private JwtUtils jwtUtils;
    private RedisUtil redisUtil;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils(
                "test-only-secret-that-is-at-least-thirty-two-bytes",
                "doc-ai-test", 60_000, 120_000);
        redisUtil = mock(RedisUtil.class);
        userService = spy(new UserServiceImpl());
        ReflectionTestUtils.setField(userService, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(userService, "redisUtil", redisUtil);
    }

    @Test
    void refreshTokenIsConsumedAndRotated() {
        String refreshToken = jwtUtils.generateRefreshToken(7L);
        Claims claims = jwtUtils.parseRefreshToken(refreshToken);
        when(redisUtil.getAndDelete("auth:refresh:" + claims.getId())).thenReturn("7");

        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setRole("USER");
        user.setStatus(1);
        doReturn(user).when(userService).getById(7L);

        LoginVO refreshed = userService.refreshToken(refreshToken);

        assertThat(jwtUtils.validateAccessToken(refreshed.getAccessToken())).isTrue();
        assertThat(jwtUtils.validateRefreshToken(refreshed.getRefreshToken())).isTrue();
        assertThat(refreshed.getRefreshToken()).isNotEqualTo(refreshToken);
        verify(redisUtil).set(anyString(), eq("7"), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void consumedRefreshTokenCannotBeReplayed() {
        String refreshToken = jwtUtils.generateRefreshToken(7L);
        Claims claims = jwtUtils.parseRefreshToken(refreshToken);
        when(redisUtil.getAndDelete("auth:refresh:" + claims.getId())).thenReturn(null);

        assertThatThrownBy(() -> userService.refreshToken(refreshToken))
                .isInstanceOf(BusinessException.class);
    }
}
