package com.streaming.music.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 로그인 성공 시 JWT를 발급하고 화이트리스트로 검증된 redirect_uri로 리다이렉트한다 (PRD-05 §6.1).
 * 토큰·쿠키 값·redirect_uri 원문은 로그에 남기지 않는다.
 */
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    static final String ERROR_INVALID_REDIRECT_URI = "invalid_redirect_uri";
    static final String ERROR_LOGIN_FAILED = "login_failed";
    static final String ERROR_SERVER = "server_error";

    private final JwtTokenProvider jwtTokenProvider;
    private final RedirectUriPolicy redirectUriPolicy;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieRepository;

    public OAuth2AuthenticationSuccessHandler(JwtTokenProvider jwtTokenProvider,
                                              RedirectUriPolicy redirectUriPolicy,
                                              HttpCookieOAuth2AuthorizationRequestRepository cookieRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redirectUriPolicy = redirectUriPolicy;
        this.cookieRepository = cookieRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) {
        // 1. 읽기 -> 2. 검증 -> 3. 쿠키 삭제 -> 4. 응답 순서를 모든 경로가 공유한다
        Optional<String> requested = cookieRepository.loadRedirectUri(request);
        String location = buildLocation(requested, authentication);
        cookieRepository.removeAuthorizationRequestCookies(request, response);
        RedirectResponses.sendRedirect(response, location);
    }

    private String buildLocation(Optional<String> requested, Authentication authentication) {
        String target;
        if (requested.isEmpty()) {
            target = redirectUriPolicy.getDefaultRedirectUri();
        } else if (redirectUriPolicy.isAllowed(requested.get())) {
            target = requested.get();
        } else {
            log.warn("화이트리스트에 없는 redirect_uri 요청을 거부했습니다");
            return withParam(redirectUriPolicy.getDefaultRedirectUri(), "error", ERROR_INVALID_REDIRECT_URI);
        }

        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof AuthenticatedOidcUser user)) {
            log.warn("로그인 성공 principal이 예상 타입이 아닙니다");
            return withParam(target, "error", ERROR_LOGIN_FAILED);
        }
        try {
            String token = jwtTokenProvider.createToken(user.getUserId(), user.getRole());
            return withParam(target, "token", token);
        } catch (RuntimeException e) {
            log.warn("JWT 발급 실패: {}", e.getClass().getSimpleName());
            return withParam(target, "error", ERROR_SERVER);
        }
    }

    /** 화이트리스트 값은 쿼리·프래그먼트가 없음이 보장되므로 단순 이어붙이되 값은 인코딩한다. */
    static String withParam(String uri, String name, String value) {
        return uri + "?" + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 리다이렉트 응답 공통 처리(토큰 노출 최소화 헤더 포함). */
    static final class RedirectResponses {

        private RedirectResponses() {
        }

        static void sendRedirect(HttpServletResponse response, String location) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader(HttpHeaders.LOCATION, location);
        }
    }
}
