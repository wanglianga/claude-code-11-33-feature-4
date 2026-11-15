package com.trace.recall.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 外部处置系统接入拦截器：/external/** 必须携带与配置一致的 X-API-Key。
 * 内部工作台 /api/** 不经过该拦截器。
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final String expectedKey;

    public ApiKeyInterceptor(@Value("${app.external.api-key}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String key = request.getHeader("X-API-Key");
        if (key != null && key.equals(expectedKey)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":401,\"message\":\"缺少或无效的 X-API-Key\"}");
        return false;
    }
}
