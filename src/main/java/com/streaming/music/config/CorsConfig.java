package com.streaming.music.config;

import com.streaming.music.config.properties.CorsProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS 설정 (PRD-05 §7.3).
 * app.cors.allowed-origins가 비어 있으면 어떤 경로에도 설정을 등록하지 않아 CORS를 적용하지 않는다.
 * 값이 있으면 해당 오리진에 한해 GET, POST, OPTIONS와 Authorization, Content-Type을 허용한다(allowCredentials=false).
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    /** 프리플라이트 응답 캐시 시간(초) */
    static final long PREFLIGHT_MAX_AGE_SECONDS = 3600L;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = corsProperties.allowedOrigins();
        if (origins.isEmpty()) {
            // 설정을 등록하지 않으면 CorsFilter가 요청을 그대로 통과시킨다(헤더 미부착).
            return source;
        }
        // 잘못된 설정은 기동 시 실패한다(fail-fast)
        origins.forEach(CorsConfig::validateOrigin);

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(PREFLIGHT_MAX_AGE_SECONDS);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** 오리진은 scheme://host[:port] 형식만 허용한다. 와일드카드, null, 경로, 끝 슬래시는 거부한다. */
    static void validateOrigin(String origin) {
        if ("*".equals(origin) || "null".equalsIgnoreCase(origin)) {
            throw new IllegalArgumentException("app.cors.allowed-origins에 '" + origin + "'는 허용되지 않습니다.");
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("app.cors.allowed-origins 형식이 잘못되었습니다: " + origin, e);
        }
        boolean validForm = uri.getScheme() != null
                && ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getRawPath().isEmpty()
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        if (!validForm) {
            throw new IllegalArgumentException(
                    "app.cors.allowed-origins는 scheme://host[:port] 형식이어야 합니다: " + origin);
        }
    }
}
