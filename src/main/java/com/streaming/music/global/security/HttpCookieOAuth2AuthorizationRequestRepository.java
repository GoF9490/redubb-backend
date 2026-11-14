package com.streaming.music.global.security;

import com.streaming.music.global.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 인가 요청(state 포함)과 redirect_uri를 HttpSession 대신 쿠키에 저장하는 저장소 (PRD-05 §6.1-3).
 * 멀티노드 환경에서 콜백이 다른 노드로 분산되어도 인가 요청을 복원할 수 있다.
 *
 * <p>직렬화: Java 직렬화는 역직렬화 취약점(원격 코드 실행) 위험이 있어 사용하지 않는다.
 * 전용 DTO를 JSON(Jackson, 다형성 타이핑 없음)으로 변환한 뒤 Base64URL(무패딩)로 인코딩하며,
 * 복원은 DTO 필드만 읽어 빌더로 재구성한다.
 *
 * <p>무결성 서명(HMAC)을 하지 않는 근거(MVP): 쿠키는 브라우저와 state를 묶는 역할이다.
 * 서명 여부와 무관하게 공격자는 정상 흐름에서 받은 유효 쿠키를 그대로 복사할 수 있어
 * 서명은 쿠키 심기 공격을 막지 못한다. 서명이 막는 필드 조작(redirectUri, clientId)은
 * Google의 등록 redirect 검증과 코드 교환 단계에서 이미 거부된다.
 * 쿠키는 HttpOnly, SameSite=Lax, 180초 만료로 노출 범위를 제한한다.
 *
 * <p>redirect_uri 쿠키는 값을 검증하지 않고 저장만 한다(화이트리스트 검증은 성공 핸들러 책임).
 * 값은 Base64URL로 인코딩하므로 CR/LF·세미콜론에 의한 헤더 인젝션이 불가능하다.
 * 쿠키 값과 redirect_uri 원문은 로그에 남기지 않는다.
 */
@Slf4j
public class HttpCookieOAuth2AuthorizationRequestRepository
		implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	public static final String AUTHORIZATION_REQUEST_COOKIE_NAME = "oauth2_auth_request";
	public static final String REDIRECT_URI_COOKIE_NAME = "oauth2_redirect_uri";
	public static final String REDIRECT_URI_PARAM_NAME = "redirect_uri";

	static final int MAX_COOKIE_VALUE_LENGTH = 3072;
	static final int MAX_REDIRECT_URI_LENGTH = 512;

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private final int cookieMaxAgeSeconds;

	public HttpCookieOAuth2AuthorizationRequestRepository(JwtProperties jwtProperties) {
		this.cookieMaxAgeSeconds = jwtProperties.authorizationCookieMaxAgeSeconds();
	}

	/** 쿠키에 저장할 인가 요청 필드. grantType/responseType은 고정값이라 저장하지 않는다. */
	record StoredAuthorizationRequest(
			String authorizationUri,
			String clientId,
			String redirectUri,
			Set<String> scopes,
			String state,
			Map<String, String> additionalParameters,
			Map<String, String> attributes,
			String authorizationRequestUri
	) {
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		return findCookieValue(request, AUTHORIZATION_REQUEST_COOKIE_NAME)
				.map(this::deserializeAuthorizationRequest)
				.orElse(null);
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
			HttpServletRequest request, HttpServletResponse response) {
		if (authorizationRequest == null) {
			removeAuthorizationRequestCookies(request, response);
			return;
		}

		String serialized = serializeAuthorizationRequest(authorizationRequest);
		if (serialized == null || serialized.length() > MAX_COOKIE_VALUE_LENGTH) {
			log.warn("OAuth2 인가 요청 쿠키를 저장하지 못했습니다: 직렬화 실패 또는 크기 초과");
			removeAuthorizationRequestCookies(request, response);
			return;
		}
		addCookie(response, request, AUTHORIZATION_REQUEST_COOKIE_NAME, serialized, cookieMaxAgeSeconds);

		String redirectUri = request.getParameter(REDIRECT_URI_PARAM_NAME);
		if (redirectUri == null || redirectUri.isBlank() || redirectUri.length() > MAX_REDIRECT_URI_LENGTH) {
			// 값이 없거나 비정상이면 쿠키를 만들지 않는다(이전 쿠키가 남지 않도록 삭제만 지시)
			addCookie(response, request, REDIRECT_URI_COOKIE_NAME, "", 0);
			return;
		}
		addCookie(response, request, REDIRECT_URI_COOKIE_NAME, encode(redirectUri), cookieMaxAgeSeconds);
	}

	/** 요청을 반환하고 인가 요청 쿠키만 삭제한다. redirect_uri 쿠키는 성공/실패 핸들러가 읽은 뒤 삭제한다. */
	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
		addCookie(response, request, AUTHORIZATION_REQUEST_COOKIE_NAME, "", 0);
		return authorizationRequest;
	}

	/** 인가 요청·redirect_uri 쿠키를 모두 삭제한다(T6-5 핸들러에서 호출). */
	public void removeAuthorizationRequestCookies(HttpServletRequest request, HttpServletResponse response) {
		addCookie(response, request, AUTHORIZATION_REQUEST_COOKIE_NAME, "", 0);
		addCookie(response, request, REDIRECT_URI_COOKIE_NAME, "", 0);
	}

	/** redirect_uri 쿠키의 원문을 복원한다. 쿠키가 없거나 손상되었으면 빈 값을 반환한다. */
	public Optional<String> loadRedirectUri(HttpServletRequest request) {
		return findCookieValue(request, REDIRECT_URI_COOKIE_NAME)
				.filter(value -> value.length() <= MAX_COOKIE_VALUE_LENGTH)
				.map(value -> {
					try {
						String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
						return decoded.isBlank() || decoded.length() > MAX_REDIRECT_URI_LENGTH ? null : decoded;
					} catch (IllegalArgumentException e) {
						log.warn("redirect_uri 쿠키 디코딩 실패");
						return null;
					}
				});
	}

	private String serializeAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest) {
		try {
			StoredAuthorizationRequest stored = new StoredAuthorizationRequest(
					authorizationRequest.getAuthorizationUri(),
					authorizationRequest.getClientId(),
					authorizationRequest.getRedirectUri(),
					new LinkedHashSet<>(authorizationRequest.getScopes()),
					authorizationRequest.getState(),
					toStringMap(authorizationRequest.getAdditionalParameters()),
					toStringMap(authorizationRequest.getAttributes()),
					authorizationRequest.getAuthorizationRequestUri()
			);
			return encode(JSON_MAPPER.writeValueAsString(stored));
		} catch (Exception e) {
			return null;
		}
	}

	private OAuth2AuthorizationRequest deserializeAuthorizationRequest(String cookieValue) {
		if (cookieValue.length() > MAX_COOKIE_VALUE_LENGTH) {
			log.warn("OAuth2 인가 요청 쿠키가 허용 크기를 초과했습니다");
			return null;
		}
		try {
			byte[] json = Base64.getUrlDecoder().decode(cookieValue);
			StoredAuthorizationRequest stored = JSON_MAPPER.readValue(json, StoredAuthorizationRequest.class);
			if (stored == null || isBlank(stored.authorizationUri()) || isBlank(stored.clientId())
					|| isBlank(stored.state())) {
				log.warn("OAuth2 인가 요청 쿠키에 필수 필드가 없습니다");
				return null;
			}
			OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
					.authorizationUri(stored.authorizationUri())
					.clientId(stored.clientId())
					.redirectUri(stored.redirectUri())
					.state(stored.state())
					.scopes(stored.scopes() == null ? new LinkedHashSet<>() : stored.scopes())
					.additionalParameters(toObjectMap(stored.additionalParameters()))
					.attributes(toObjectMap(stored.attributes()));
			if (stored.authorizationRequestUri() != null) {
				builder.authorizationRequestUri(stored.authorizationRequestUri());
			}
			return builder.build();
		} catch (Exception e) {
			// 변조·손상 쿠키는 예외 없이 무시한다(쿠키 값은 기록하지 않음)
			log.warn("OAuth2 인가 요청 쿠키 복원 실패: {}", e.getClass().getSimpleName());
			return null;
		}
	}

	private Map<String, String> toStringMap(Map<String, Object> source) {
		Map<String, String> result = new LinkedHashMap<>();
		if (source != null) {
			source.forEach((key, value) -> {
				if (value instanceof String text) {
					result.put(key, text);
				}
			});
		}
		return result;
	}

	private Map<String, Object> toObjectMap(Map<String, String> source) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (source != null) {
			result.putAll(source);
		}
		return result;
	}

	private Optional<String> findCookieValue(HttpServletRequest request, String name) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
				return Optional.of(cookie.getValue());
			}
		}
		return Optional.empty();
	}

	private void addCookie(HttpServletResponse response, HttpServletRequest request,
			String name, String value, int maxAgeSeconds) {
		ResponseCookie cookie = ResponseCookie.from(name, value)
				.httpOnly(true)
				.secure(request.isSecure())
				.path("/")
				.maxAge(Duration.ofSeconds(maxAgeSeconds))
				.sameSite("Lax")
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	private String encode(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
