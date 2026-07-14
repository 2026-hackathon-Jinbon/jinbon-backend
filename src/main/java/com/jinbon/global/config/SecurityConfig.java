package com.jinbon.global.config;

import com.jinbon.domain.auth.service.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 설정.
 *
 * - JWT 기반 stateless 인증
 * - CORS: 개발 환경에서는 모든 오리진 허용, 프로덕션에서는 CORS_ALLOWED_ORIGINS 환경변수로 제한
 * - 인증 불필요 경로: 인증(/api/auth), 회원가입(/api/signup), 검증(/api/verify), 헬스체크
 * - Swagger UI는 개발 환경에서만 허용
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final Environment environment;

    /** CORS 허용 오리진 (프로덕션: 쉼표로 구분, 미설정 시 개발 모드로 전체 허용) */
    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        boolean isDev = Arrays.asList(environment.getActiveProfiles()).contains("dev")
                || environment.getActiveProfiles().length == 0;

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Authentication required"))
                        .accessDeniedHandler((request, response, exception) ->
                                response.sendError(HttpStatus.FORBIDDEN.value(), "Access denied")))
                .authorizeHttpRequests(auth -> {
                    // 인증 불필요 API
                    auth.requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/signup/**").permitAll()
                        .requestMatchers("/api/verify/**").permitAll()
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/favicon.ico").permitAll();

                    // Swagger/인증 테스트 페이지: 개발 환경에서만 허용
                    if (isDev) {
                        auth.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                            .requestMatchers("/auth.html").permitAll();
                    }

                    // 나머지는 인증 필요
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS 정책을 설정한다.
     * - CORS_ALLOWED_ORIGINS 환경변수가 설정되어 있으면 해당 오리진만 허용
     * - 미설정 시(개발 환경) 모든 오리진 허용
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            // 프로덕션: 명시된 오리진만 허용
            config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
            config.setAllowCredentials(true);
        } else {
            // 개발: 모든 오리진 허용 (allowCredentials=false 필수)
            config.setAllowedOrigins(List.of("*"));
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
