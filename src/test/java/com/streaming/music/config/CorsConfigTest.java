package com.streaming.music.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streaming.music.config.properties.CorsProperties;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * CorsConfig 오리진 검증과 설정 구성 단위 테스트.
 */
class CorsConfigTest {

    private final CorsConfig corsConfig = new CorsConfig();

    private CorsConfigurationSource createSource(List<String> origins) {
        return corsConfig.corsConfigurationSource(new CorsProperties(origins));
    }

    private CorsConfiguration resolve(CorsConfigurationSource source) {
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/tracks"));
    }

    @Test
    @DisplayName("null·빈 문자열·빈 리스트는 CORS 비활성(설정 없음)으로 정상 생성된다")
    void createDisabledSourceForEmptyInput() {
        assertThat(resolve(createSource(null))).isNull();
        assertThat(resolve(createSource(List.of()))).isNull();
        assertThat(resolve(createSource(List.of("")))).isNull();
    }

    @Test
    @DisplayName("공백만 있는 항목은 정규화로 제거되어 CORS 비활성이 된다")
    void treatBlankItemAsDisabled() {
        assertThat(resolve(createSource(Arrays.asList("  ", null)))).isNull();
    }

    @Test
    @DisplayName("허용 설정은 명시 오리진, 지정 메서드·헤더, credentials=false, maxAge 3600이다")
    void createEnabledConfiguration() {
        CorsConfiguration configuration =
                resolve(createSource(List.of("https://web.example.com", "http://localhost:3000")));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins())
                .containsExactly("https://web.example.com", "http://localhost:3000");
        assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "OPTIONS");
        assertThat(configuration.getAllowedHeaders()).containsExactly("Authorization", "Content-Type");
        assertThat(configuration.getAllowCredentials()).isFalse();
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "*",
        "null",
        "https://web.example.com/",
        "https://web.example.com/app",
        "https://web.example.com?a=1",
        "https://web.example.com#x",
        "web.example.com",
        "web.example.com:8080",
        "ftp://web.example.com",
        "https://user@web.example.com",
        "https://"
    })
    @DisplayName("잘못된 오리진은 빈 생성 시 IllegalArgumentException으로 실패한다")
    void rejectInvalidOrigin(String origin) {
        assertThatThrownBy(() -> createSource(List.of("https://ok.example.com", origin)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
