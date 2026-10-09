package io.github.mauludinegi.payments.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;

/** Requires a customer session ({@code Authorization: Bearer <session token>}) for orders and account endpoints. */
@Configuration
public class UserAuth implements WebMvcConfigurer, HandlerInterceptor {

    /** Request attribute holding the signed-in user's id. */
    public static final String USER_ID = "payments.userId";

    private final SessionService sessions;

    public UserAuth(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/orders", "/api/orders/**", "/api/me/**", "/api/auth/me");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID userId = sessions.resolve(bearerToken(request))
                .orElseThrow(() -> new UnauthorizedException("Sign in to continue"));
        request.setAttribute(USER_ID, userId);
        return true;
    }

    public static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
