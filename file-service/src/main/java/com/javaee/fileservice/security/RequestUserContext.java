package com.javaee.fileservice.security;

import com.javaee.common.exception.BusinessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class RequestUserContext {

    public String getRequiredUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getPrincipal() == null) {
            throw new BusinessException("用户未认证，请先登录");
        }
        String userId = authentication.getPrincipal().toString();
        if (userId.isBlank() || "anonymousUser".equalsIgnoreCase(userId)) {
            throw new BusinessException("用户未认证，请先登录");
        }
        return userId;
    }
}
