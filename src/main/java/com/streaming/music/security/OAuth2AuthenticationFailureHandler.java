package com.streaming.music.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * 로그인 실패 시 redirect_uri?error={code}로 리다이렉트한다 (PRD-05 §6.1).
 * 에러 코드는 허용 목록만 그대로 전달하고, 그 외(예외 메시지 포함)는 login_failed로 치환한다.
 */
@Slf4j
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    static final String ERROR_LOGIN_FAILED = "login_failed";

    static final Set<String> ALLOWED_ERROR_CODES = Set.of(
            "access_denied", "invalid_request", "authorization_request_not_found",
            "invalid_state_parameter", "invalid_user_info", "server_error", "temporarily_unavailable");

    private final RedirectUriPolicy redirectUriPolicy;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieRepository;

    public OAuth2AuthenticationFailureHandler(RedirectUriPolicy redirectUriPolicy,
                                              HttpCookieOAuth2AuthorizationRequestRepository cookieRepository) {
        this.redirectUriPolicy = redirectUriPolicy;
        this.cookieRepository = cookieRepository;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) {
        String errorCode = resolveErrorCode(exception);
        // 쿠키가 화이트리스트 밖이면 기본 URI로 보낸다
        String target = redirectUriPolicy.findAllowed(cookieRepository.loadRedirectUri(request))
                .orElse(redirectUriPolicy.getDefaultRedirectUri());
        cookieRepository.removeAuthorizationRequestCookies(request, response);
        log.warn("OAuth2 로그인 실패: code={}", errorCode);
        OAuth2AuthenticationSuccessHandler.RedirectResponses.sendRedirect(response,
                OAuth2AuthenticationSuccessHandler.withParam(target, "error", errorCode));
    }

    private String resolveErrorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2Exception
                && oauth2Exception.getError() != null) {
            String code = oauth2Exception.getError().getErrorCode();
            if (code != null && ALLOWED_ERROR_CODES.contains(code)) {
                return code;
            }
        }
        return ERROR_LOGIN_FAILED;
    }
}
