package io.github.mauludinegi.payments.admin;

import io.github.mauludinegi.payments.config.AdminProperties;
import io.github.mauludinegi.payments.gateway.WebhookSecrets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Requires {@code Authorization: Bearer <admin.token>} on every /api/admin request. */
@Configuration
public class AdminAuth implements WebMvcConfigurer, HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAuth.class);

    private final String token;

    public AdminAuth(AdminProperties properties) {
        this.token = properties.token();
        if (AdminProperties.DEMO_TOKEN.equals(token)) {
            log.warn("ADMIN_TOKEN is not set; the admin API accepts the demo token. Set ADMIN_TOKEN before deploying.");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String presented = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (!WebhookSecrets.matches(token, presented)) {
            throw new UnauthorizedException("Admin token missing or invalid");
        }
        return true;
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
