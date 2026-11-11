package com.streaming.music.config;

import com.streaming.music.security.JwtAuthenticationFilter;
import com.streaming.music.security.JwtTokenProvider;
import com.streaming.music.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security 설정 (PRD-05 §7.3).
 * 무상태(STATELESS)이며 MVP 정책에 따라 /api/auth/me만 인증을 요구하고 나머지는 허용한다.
 * 미인증 시 AuthenticationEntryPoint가 401 공통 JSON을 응답한다(로그인 페이지 리다이렉트 없음).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // T6-6 확장 지점: .cors(cors -> cors.configurationSource(...))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/me").authenticated()
                // 추후 권한 제어 시 /api/tracks/** 등 경로별 규칙을 anyRequest() 앞에 둔다.
                .anyRequest().permitAll()
            )
            // T6-3~T6-5 확장 지점: .oauth2Login(...) 추가 후에도 EntryPoint는 위에서 명시한 401 JSON을 유지해야 한다.
            // 필터는 빈이 아닌 직접 생성하여 서블릿 컨테이너 자동 등록(이중 실행)을 피한다.
            .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
