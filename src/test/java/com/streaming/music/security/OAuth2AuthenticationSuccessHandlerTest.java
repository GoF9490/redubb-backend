package com.streaming.music.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streaming.music.config.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

class OAuth2AuthenticationSuccessHandlerTest {

    private static final String WEB_URI = "http://localhost/auth/callback";
    private static final String APP_URI = "musicapp://oauth2/callback";
    private static final String KEY = "test-secret-key-test-secret-key-0123456789";

    private JwtTokenProvider jwtTokenProvider;
    private OAuth2AuthenticationSuccessHandler handler;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(new JwtProperties(KEY, 3_600_000L, WEB_URI, List.of(WEB_URI, APP_URI)),
                Clock.fixed(Instant.now(), ZoneOffset.UTC));
        RedirectUriPolicy policy = new RedirectUriPolicy(WEB_URI, List.of(WEB_URI, APP_URI));
        handler = new OAuth2AuthenticationSuccessHandler(jwtTokenProvider, policy,
                new HttpCookieOAuth2AuthorizationRequestRepository());

        OidcIdToken idToken = OidcIdToken.withTokenValue("t").subject("sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        AuthenticatedOidcUser user = new AuthenticatedOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, 7L, "ROLE_USER");
        authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
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
        for (String name : List.of(HttpCookieOAuth2AuthorizationRequestRepository.AUTHORIZATION_REQUEST_COOKIE_NAME,
                HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_COOKIE_NAME)) {
            assertThat(setCookies).anyMatch(c -> c.startsWith(name + "=;") && c.contains("Max-Age=0"));
        }
    }

    private void assertSecureHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    static Stream<String> allowedUris() {
        return Stream.of(WEB_URI, APP_URI);
    }

    @ParameterizedTest
    @MethodSource("allowedUris")
    @DisplayName("화이트리스트의 웹·앱 redirect_uri는 {uri}?token={jwt}로 리다이렉트한다")
    void redirectWithTokenToAllowedUri(String uri) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithRedirect(uri), response, authentication);

        String location = response.getHeader("Location");
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(location).startsWith(uri + "?token=").doesNotContain("\n", "\r");
        String token = location.substring((uri + "?token=").length());
        assertThat(jwtTokenProvider.parseToken(token)).isPresent();
        assertThat(jwtTokenProvider.parseToken(token).get().userId()).isEqualTo(7L);
        assertSecureHeaders(response);
        assertCookiesDeleted(response);
    }

    @Test
    @DisplayName("redirect_uri 쿠키가 없으면 기본 URI로 ?token= 을 붙여 리다이렉트한다")
    void redirectToDefaultWhenNoCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithRedirect(null), response, authentication);

        assertThat(response.getHeader("Location")).startsWith(WEB_URI + "?token=");
        assertCookiesDeleted(response);
    }

    static Stream<String> forgedUris() {
        return Stream.of(
                "https://evil.com/auth/callback",
                "javascript:alert(1)",
                "//evil.com/auth/callback",
                "http://localhost.evil.com/auth/callback",
                "http://localhost/auth/callback@evil.com",
                "http://localhost@evil.com/auth/callback",
                "http://localhost/auth/callback#frag",
                "http://localhost/auth/callback?x=1",
                "HTTP://LOCALHOST/AUTH/CALLBACK",
                "http://localhost/auth/Callback",
                " http://localhost/auth/callback",
                "http://localhost/auth/callback ",
                "http://localhost/auth/callback\r\nSet-Cookie: a=b",
                "http://localhost/auth/%2e/callback",
                "http://localhost/auth/%63allback",
                "http://localhost/auth/callback/",
                "http://localhost:80/auth/callback",
                "http://localhost/auth",
                "musicapp://oauth2/callback/",
                "musicapp://oauth2/callback?x=1",
                "MUSICAPP://oauth2/callback",
                "musicapp://oauth2.evil.com/callback",
                "http://localhost/auth/callback,musicapp://oauth2/callback");
    }

    @ParameterizedTest
    @MethodSource("forgedUris")
    @DisplayName("화이트리스트와 완전 일치하지 않는 URI는 토큰 없이 기본 URI?error=invalid_redirect_uri로 보낸다")
    void rejectForgedRedirectUri(String uri) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithRedirect(uri), response, authentication);

        String location = response.getHeader("Location");
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(location).isEqualTo(WEB_URI + "?error=invalid_redirect_uri");
        assertThat(location).doesNotContain("token");
        assertSecureHeaders(response);
        assertCookiesDeleted(response);
    }

    @Test
    @DisplayName("빈 값 redirect_uri는 정책상 허용되지 않는다")
    void rejectEmptyRedirectUri() {
        RedirectUriPolicy policy = new RedirectUriPolicy(WEB_URI, List.of(WEB_URI));

        assertThat(policy.isAllowed("")).isFalse();
        assertThat(policy.isAllowed(null)).isFalse();
        assertThat(policy.findAllowed(Optional.of(" "))).isEmpty();
    }

    @Test
    @DisplayName("손상된 redirect_uri 쿠키 값은 토큰이 아닌 기본 URI 경로로만 처리된다")
    void ignoreBrokenCookieValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_COOKIE_NAME,
                "!!!not-base64!!!"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getHeader("Location")).startsWith(WEB_URI + "?token=");
        assertCookiesDeleted(response);
    }

    @Test
    @DisplayName("principal이 AuthenticatedOidcUser가 아니면 토큰 없이 error로 리다이렉트한다")
    void redirectWithErrorWhenPrincipalInvalid() {
        Authentication other = new UsernamePasswordAuthenticationToken("someone", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(requestWithRedirect(APP_URI), response, other);

        assertThat(response.getHeader("Location")).isEqualTo(APP_URI + "?error=login_failed");
        assertCookiesDeleted(response);
    }

    @Test
    @DisplayName("JWT 발급이 실패하면 server_error로 리다이렉트하고 세션을 만들지 않는다")
    void redirectWithErrorWhenTokenCreationFails() {
        JwtTokenProvider failing = mock(JwtTokenProvider.class);
        when(failing.createToken(7L, "ROLE_USER")).thenThrow(new IllegalStateException("boom"));
        OAuth2AuthenticationSuccessHandler failingHandler = new OAuth2AuthenticationSuccessHandler(failing,
                new RedirectUriPolicy(WEB_URI, List.of(WEB_URI, APP_URI)),
                new HttpCookieOAuth2AuthorizationRequestRepository());
        MockHttpServletRequest request = requestWithRedirect(WEB_URI);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failingHandler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getHeader("Location")).isEqualTo(WEB_URI + "?error=server_error");
        assertThat(request.getSession(false)).isNull();
        assertCookiesDeleted(response);
    }
}
