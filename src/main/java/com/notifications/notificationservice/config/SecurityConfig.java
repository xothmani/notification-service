package com.notifications.notificationservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String DEFAULT_DEV_TOKEN = "dev-only-change-in-production";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           InternalTokenFilter internalTokenFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/internal/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(
                        internalTokenFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public InternalTokenFilter internalTokenFilter(
            @Value("${internal.token}") String internalToken) {
        return new InternalTokenFilter(internalToken);
    }

    // Prevent Spring Boot from registering the filter in the default servlet chain
    // (it is already registered exclusively inside the security filter chain above)
    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(
            InternalTokenFilter filter) {
        FilterRegistrationBean<InternalTokenFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Slf4j
    public static class InternalTokenFilter extends OncePerRequestFilter {

        private final String internalToken;

        public InternalTokenFilter(String internalToken) {
            // Warn loudly if the default development token is active.
            // Fail-fast in production by throwing if the default is detected
            // and the active profile is not "dev" or "test".
            if (DEFAULT_DEV_TOKEN.equals(internalToken)) {
                String profiles = System.getProperty("spring.profiles.active", "");
                if (!profiles.contains("dev") && !profiles.contains("test")) {
                    log.error("SECURITY: INTERNAL_TOKEN is set to the default development value. " +
                              "Set the INTERNAL_TOKEN environment variable before deploying.");
                }
                log.warn("SECURITY WARNING: INTERNAL_TOKEN is using the default development value. " +
                         "This must be changed before production deployment.");
            }
            this.internalToken = internalToken;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {

            String path = request.getRequestURI();

            if (path.startsWith("/api/internal/")) {
                String token = request.getHeader("X-Internal-Token");

                // Constant-time comparison to prevent timing-based token enumeration attacks.
                // MessageDigest.isEqual operates in fixed time regardless of where bytes differ.
                if (token == null || !MessageDigest.isEqual(
                        token.getBytes(), internalToken.getBytes())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"status\":\"ERROR\"," +
                                    "\"message\":\"Unauthorized\"," +
                                    "\"data\":null}"
                    );
                    return;
                }

                // Token is valid — set authentication so Spring Security's
                // .authenticated() rule passes for /api/internal/** endpoints
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                "internal-service", null,
                                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))
                        );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }

            filterChain.doFilter(request, response);
        }
    }
}
