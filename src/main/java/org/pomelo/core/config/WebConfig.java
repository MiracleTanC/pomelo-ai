package org.pomelo.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置（前后端分离模式）：
 * - CORS：仅允许配置的前端域名访问 /api/**
 * - 不再提供 SPA fallback —— 前端独立部署，由 Nginx/Vite 处理路由
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 允许的前端来源（逗号分隔），从配置读取，支持开发和生产环境 */
    @Value("${pomelo.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
