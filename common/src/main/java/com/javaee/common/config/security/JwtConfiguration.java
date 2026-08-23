package com.javaee.common.config.security;

import com.javaee.common.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the JWT codec from external configuration. The application fails closed
 * when the signing secret is missing or too short.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {

    @Bean
    public JwtUtils jwtUtils(
            @Value("${security.jwt.secret:}") String secret,
            @Value("${security.jwt.previous-secret:}") String previousSecret,
            @Value("${security.jwt.issuer:doc-ai}") String issuer,
            @Value("${security.jwt.access-token-expiration-ms:1800000}") long accessTokenExpiration,
            @Value("${security.jwt.refresh-token-expiration-ms:604800000}") long refreshTokenExpiration) {
        return new JwtUtils(secret, previousSecret, issuer, accessTokenExpiration, refreshTokenExpiration);
    }
}
