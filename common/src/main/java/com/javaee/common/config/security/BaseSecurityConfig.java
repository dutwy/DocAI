package com.javaee.common.config.security;

import com.javaee.common.constant.ErrorCodeEnum;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 基础安全配置类
 * 提供通用的安全设置，供各服务继承使用
 */
@Configuration
@EnableWebSecurity
public class BaseSecurityConfig {

    protected final JwtAuthenticationFilter jwtAuthenticationFilter;
    protected final SecurityErrorResponseWriter securityErrorResponseWriter;

    /**
     * 构造函数注入JWT认证过滤器
     * @param jwtAuthenticationFilter JWT认证过滤器
     */
    public BaseSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                              SecurityErrorResponseWriter securityErrorResponseWriter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityErrorResponseWriter = securityErrorResponseWriter;
    }

    /**
     * 应用基础安全配置
     * @param http HttpSecurity
     * @throws Exception 异常
     */
    protected void applyBaseSecurityConfig(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint((request, response, exception) ->
                            securityErrorResponseWriter.write(response, ErrorCodeEnum.UNAUTHORIZED, exception.getMessage()))
                    .accessDeniedHandler((request, response, exception) ->
                            securityErrorResponseWriter.write(response, ErrorCodeEnum.FORBIDDEN, exception.getMessage())))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    }

}
