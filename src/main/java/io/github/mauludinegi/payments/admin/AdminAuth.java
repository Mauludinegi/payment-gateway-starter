package io.github.mauludinegi.payments.admin;

import io.github.mauludinegi.payments.auth.ForbiddenException;
import io.github.mauludinegi.payments.auth.SessionService;
import io.github.mauludinegi.payments.auth.UnauthorizedException;
import io.github.mauludinegi.payments.auth.User;
import io.github.mauludinegi.payments.auth.UserAuth;
import io.github.mauludinegi.payments.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;

/**
 * Requires a session whose user has the ADMIN role on every /api/admin request. The role is read from the
 * database each time, so taking it away works immediately, without waiting for the session to expire.
 */
@Configuration
public class AdminAuth implements WebMvcConfigurer, HandlerInterceptor {

    private final SessionService sessions;
    private final UserRepository users;

    public AdminAuth(SessionService sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID userId = sessions.resolve(UserAuth.bearerToken(request))
                .orElseThrow(() -> new UnauthorizedException("Sign in to continue"));
        User user = users.findById(userId).orElseThrow(() -> new UnauthorizedException("Sign in to continue"));
        if (!user.isAdmin()) {
            throw new ForbiddenException("This needs the ADMIN role");
        }
        request.setAttribute(UserAuth.USER_ID, userId);
        return true;
    }
}
