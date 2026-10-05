package com.typerush.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typerush.security.JwtAuthFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless bearer-token security.
 *
 * <p>CSRF protection is off deliberately: there is no cookie or session for a forged request to ride
 * on, so the classic CSRF attack has nothing to hijack. Credentials are never attached to CORS
 * either, since the token travels in an explicit header.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final GameProperties properties;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, GameProperties properties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Preflight must never require a token.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                // Logout must also be reachable anonymously: the whole point may be to
                                // drop a session whose access token has already expired.
                                "/api/auth/logout")
                                .permitAll()
                        .requestMatchers("/api/health", "/api/texts", "/api/leaderboard").permitAll()
                        // Public aggregates for one player. The race-by-race history under the same
                        // prefix is deliberately NOT matched: one path segment only.
                        .requestMatchers("/api/players/*").permitAll()
                        // The upgrade is authorized by AuthHandshakeInterceptor, which can inspect the
                        // query string; the filter chain never sees the token.
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonEntryPoint(HttpStatus.UNAUTHORIZED, "unauthorized",
                                "authentication required"))
                        .accessDeniedHandler(jsonDeniedHandler()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Cost is fixed rather than the library default of 10, matching the {@code AUTH_BCRYPT_COST} the
     * rest of the auth code documents.
     */
    @Bean
    PasswordEncoder passwordEncoder(GameProperties properties) {
        return new BCryptPasswordEncoder(properties.getAuth().getBcryptCost());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // No cookies are used, so the browser must not send credentials on cross-origin calls.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Returns JSON rather than a redirect or an HTML error page, so the client can react to it. */
    private AuthenticationEntryPoint jsonEntryPoint(HttpStatus status, String code, String message) {
        return (request, response, exception) -> write(response, status, code, message);
    }

    private AccessDeniedHandler jsonDeniedHandler() {
        return (request, response, exception) ->
                write(response, HttpStatus.FORBIDDEN, "forbidden", "you do not have access to this resource");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(response.getOutputStream(),
                java.util.Map.of("code", code, "message", message));
    }
}