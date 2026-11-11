package com.streaming.music.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 최소 Security 골격 (PRD-05 §7.3).
 * 무상태(STATELESS)이며 MVP 정책에 따라 모든 요청을 허용한다.
 * 인증 관련 설정(oauth2Login, JwtAuthenticationFilter, CORS, EntryPoint)은 Phase 6(T6-x)에서 추가한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // T6-2 확장 지점: 여기에 .requestMatchers("/api/auth/me").authenticated() 를 추가한다.
                // 추후 권한 제어 시 /api/tracks/** 등 경로별 규칙을 anyRequest() 앞에 둔다.
                .anyRequest().permitAll()
            );

        return http.build();
    }
}
