package com.streaming.music.global.security;

import com.streaming.music.global.properties.JwtProperties;
import java.util.List;
import java.util.Optional;

/**
 * 로그인 후 이동할 redirect_uri 검증 정책 (Open Redirect 방지, PRD-05 §6.1).
 *
 * <p>비교는 화이트리스트와의 문자열 완전 일치(대소문자 구분)만 사용한다.
 * 요청 값은 trim·소문자화·디코딩·URI 파싱 등 어떤 정규화도 하지 않는다(파싱 불일치 우회 방지).
 * 설정이 잘못되면 생성 시점(기동)에 실패한다.
 */
public class RedirectUriPolicy {

	private final String defaultRedirectUri;
	private final List<String> allowedRedirectUris;

	public RedirectUriPolicy(JwtProperties jwtProperties) {
		this(jwtProperties.defaultRedirectUri(), jwtProperties.allowedRedirectUris());
	}

	public RedirectUriPolicy(String defaultRedirectUri, List<String> allowedRedirectUris) {
		if (allowedRedirectUris == null || allowedRedirectUris.isEmpty()) {
			throw new IllegalStateException("app.jwt.allowed-redirect-uris가 비어 있습니다.");
		}
		if (defaultRedirectUri == null || defaultRedirectUri.isBlank()) {
			throw new IllegalStateException("app.jwt.default-redirect-uri가 설정되지 않았습니다.");
		}
		for (String uri : allowedRedirectUris) {
			validateEntry(uri);
		}
		if (!allowedRedirectUris.contains(defaultRedirectUri)) {
			throw new IllegalStateException("app.jwt.default-redirect-uri는 allowed-redirect-uris에 포함되어야 합니다.");
		}
		this.defaultRedirectUri = defaultRedirectUri;
		this.allowedRedirectUris = List.copyOf(allowedRedirectUris);
	}

	private void validateEntry(String uri) {
		if (uri == null || uri.isEmpty()) {
			throw new IllegalStateException("allowed-redirect-uris에 빈 항목이 있습니다.");
		}
		for (int i = 0; i < uri.length(); i++) {
			char ch = uri.charAt(i);
			if (ch == '?' || ch == '#' || ch == '\\' || Character.isWhitespace(ch) || Character.isISOControl(ch)) {
				throw new IllegalStateException(
						"allowed-redirect-uris 항목에 허용되지 않는 문자(? # \\ 공백 제어문자)가 있습니다.");
			}
		}
	}

	/** 요청 값이 화이트리스트와 완전히 일치하는지 확인한다. */
	public boolean isAllowed(String redirectUri) {
		return redirectUri != null && allowedRedirectUris.contains(redirectUri);
	}

	public String getDefaultRedirectUri() {
		return defaultRedirectUri;
	}

	/** 쿠키 값이 허용되면 그 값을, 없거나 허용되지 않으면 empty를 반환한다. */
	public Optional<String> findAllowed(Optional<String> requested) {
		return requested.filter(this::isAllowed);
	}
}
