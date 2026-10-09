package io.github.mauludinegi.payments.api;

import io.github.mauludinegi.payments.service.ExpiryJob;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Lets traffic drive the expiry job on hosts that pause the app between requests. */
@Component
@ConditionalOnProperty(name = "payments.expiry-on-request", havingValue = "true")
public class ExpiryOnRequestFilter extends OncePerRequestFilter {

    private final ExpiryJob expiry;

    public ExpiryOnRequestFilter(ExpiryJob expiry) {
        this.expiry = expiry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        expiry.runIfDue();
        chain.doFilter(request, response);
    }
}
