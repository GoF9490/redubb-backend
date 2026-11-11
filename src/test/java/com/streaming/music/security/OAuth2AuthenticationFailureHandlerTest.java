package com.streaming.music.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OAuth2AuthenticationFailureHandlerTest {

    private static final String WEB_URI = "http://localhost/auth/callback";
    private static final String APP_URI = "musicapp://oauth2/callback";

    private OAuth2AuthenticationFailureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2AuthenticationFailureHandler(new RedirectUriPolicy(WEB_URI, List.of(WEB_URI, APP_URI)),
                new HttpCookieOAuth2AuthorizationRequestRepository());
    }

    private MockHttpServletRequest requestWithRedirect(String redirectUri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (redirectUri != null) {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(redirectUri.getBytes(StandardCharsets.UTF_8));
            request.setCookies(new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_COOKIE_NAME,
                    encoded));
        }
        return request;
    }

    private void assertCookiesDeleted(MockHttpServletResponse response) {
        List<String> setCookies = response.getHeaders("Set-Cookie");
        assertThat(setCookies).anyMatch(c -> c.startsWith("oauth2_auth_request=;") && c.contains("Max-Age=0"));
        assertThat(setCookies).anyMatch(c -> c.startsWith("oauth2_redirect_uri=;") && c.contains("Max-Age=0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"access_denied", "invalid_request", "authorization_request_not_found",
            "invalid_state_parameter", "invalid_user_info", "server_error", "temporarily_unavailable"})
    @DisplayName("허용 목록 에러 코드는 그대로 ?error= 로 전달한다")
    void redirectWithAllowedErrorCode(String code) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(requestWithRedirect(APP_URI), response,
                new OAuth2AuthenticationException(new OAuth2Error(code)));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo(APP_URI + "?error=" + code);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertCookiesDeleted(response);
    }

    @Test
    @DisplayName("허용 목록 밖 코드와 메시지는 login_failed로 치환되어 사용자 입력이 반영되지 않는다")
    void replaceUnknownErrorCode() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String evil = "x\r\nSet-Cookie: a=b&redirect=http://evil.com";

        handler.onAuthenticationFailure(requestWithRedirect(WEB_URI), response,
                new OAuth2AuthenticationException(new OAuth2Error(evil, evil, "http://evil.com"), evil));

        String location = response.getHeader("Location");
        assertThat(location).isEqualTo(WEB_URI + "?error=login_failed");
        assertThat(location).doesNotContain("evil", "\n", "\r");
    }

    @Test
    @DisplayName("OAuth2 예외가 아니면 예외 메시지와 무관하게 login_failed로 처리한다")
    void replaceNonOAuth2Exception() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(requestWithRedirect(WEB_URI), response,
                new BadCredentialsException("http://evil.com secret"));

        assertThat(response.getHeader("Location")).isEqualTo(WEB_URI + "?error=login_failed");
    }

    @Test
    @DisplayName("쿠키 redirect_uri가 화이트리스트 밖이면 기본 URI로 보낸다")
    void useDefaultUriWhenCookieNotAllowed() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(requestWithRedirect("https://evil.com/cb"), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getHeader("Location")).isEqualTo(WEB_URI + "?error=access_denied");
        assertCookiesDeleted(response);
    }

    @Test
    @DisplayName("쿠키가 없으면 기본 URI로 보낸다")
    void useDefaultUriWhenNoCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(requestWithRedirect(null), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getHeader("Location")).isEqualTo(WEB_URI + "?error=access_denied");
        assertCookiesDeleted(response);
    }
}
