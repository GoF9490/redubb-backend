package com.streaming.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedirectUriPolicyTest {

    private static final String WEB_URI = "http://localhost/auth/callback";

    @Test
    @DisplayName("유효한 설정은 생성되고 완전 일치만 허용한다")
    void createWithValidConfig() {
        RedirectUriPolicy policy = new RedirectUriPolicy(WEB_URI, List.of(WEB_URI, "musicapp://oauth2/callback"));

        assertThat(policy.isAllowed(WEB_URI)).isTrue();
        assertThat(policy.isAllowed("musicapp://oauth2/callback")).isTrue();
        assertThat(policy.isAllowed(WEB_URI.toUpperCase())).isFalse();
        assertThat(policy.getDefaultRedirectUri()).isEqualTo(WEB_URI);
    }

    @Test
    @DisplayName("화이트리스트가 비어 있으면 기동에 실패한다")
    void failWhenAllowedListEmpty() {
        assertThatThrownBy(() -> new RedirectUriPolicy(WEB_URI, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RedirectUriPolicy(WEB_URI, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기본 URI가 없거나 화이트리스트에 없으면 기동에 실패한다")
    void failWhenDefaultInvalid() {
        assertThatThrownBy(() -> new RedirectUriPolicy(null, List.of(WEB_URI)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RedirectUriPolicy(" ", List.of(WEB_URI)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RedirectUriPolicy("http://localhost/other", List.of(WEB_URI)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/cb?x=1", "http://localhost/cb#f", "http://localhost/ cb",
            "http://localhost/cb\n", "http://localhost\\cb", "http://localhost/cb\t"})
    @DisplayName("항목에 ? # 공백 제어문자 \\ 가 있으면 기동에 실패한다")
    void failWhenEntryHasForbiddenCharacter(String entry) {
        assertThatThrownBy(() -> new RedirectUriPolicy(entry, List.of(entry)))
                .isInstanceOf(IllegalStateException.class);
    }
}
