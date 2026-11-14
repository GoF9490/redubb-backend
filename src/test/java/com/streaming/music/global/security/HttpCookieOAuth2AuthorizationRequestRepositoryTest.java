package com.streaming.music.global.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.streaming.music.global.security.HttpCookieOAuth2AuthorizationRequestRepository.AUTHORIZATION_REQUEST_COOKIE_NAME;
import static com.streaming.music.global.security.HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_COOKIE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

class HttpCookieOAuth2AuthorizationRequestRepositoryTest {

	private HttpCookieOAuth2AuthorizationRequestRepository repository;
	private MockHttpServletRequest request;
	private MockHttpServletResponse response;

	@BeforeEach
	void setUp() {
		repository = new HttpCookieOAuth2AuthorizationRequestRepository();
		request = new MockHttpServletRequest();
		response = new MockHttpServletResponse();
	}

	private OAuth2AuthorizationRequest createAuthorizationRequest() {
		return OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
				.clientId("test-client-id")
				.redirectUri("http://localhost/login/oauth2/code/google")
				.scopes(new LinkedHashSet<>(List.of("profile", "email")))
				.state("state-value-123")
				.additionalParameters(Map.of("custom", "value"))
				.attributes(Map.of("registration_id", "google"))
				.authorizationRequestUri("https://accounts.google.com/o/oauth2/v2/auth?response_type=code"
						+ "&client_id=test-client-id&scope=profile%20email&state=state-value-123"
						+ "&redirect_uri=http://localhost/login/oauth2/code/google")
				.build();
	}

	/** 응답의 Set-Cookie 헤더에서 이름이 일치하는 헤더 문자열을 찾는다. */
	private Optional<String> findSetCookie(String name) {
		return response.getHeaders("Set-Cookie").stream()
				.filter(header -> header.startsWith(name + "="))
				.reduce((first, second) -> second);
	}

	private String getSetCookieValue(String name) {
		String header = findSetCookie(name).orElseThrow();
		String pair = header.split(";", 2)[0];
		return pair.substring(name.length() + 1);
	}

	private MockHttpServletRequest requestWithCookie(String name, String value) {
		MockHttpServletRequest next = new MockHttpServletRequest();
		next.setCookies(new Cookie(name, value));
		return next;
	}

	private String encodeBase64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String encodeBase64Url(String text) {
		return encodeBase64Url(text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("저장 후 로드하면 모든 필드가 동일한 인가 요청이 복원된다")
	void restoreSameRequestAfterSave() {
		OAuth2AuthorizationRequest original = createAuthorizationRequest();

		repository.saveAuthorizationRequest(original, request, response);
		OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(
				requestWithCookie(AUTHORIZATION_REQUEST_COOKIE_NAME, getSetCookieValue(AUTHORIZATION_REQUEST_COOKIE_NAME)));

		assertThat(loaded).isNotNull();
		assertThat(loaded.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
		assertThat(loaded.getClientId()).isEqualTo(original.getClientId());
		assertThat(loaded.getRedirectUri()).isEqualTo(original.getRedirectUri());
		assertThat(loaded.getScopes()).containsExactlyElementsOf(original.getScopes());
		assertThat(loaded.getState()).isEqualTo(original.getState());
		assertThat(loaded.getAdditionalParameters()).isEqualTo(original.getAdditionalParameters());
		assertThat(loaded.getAttributes()).isEqualTo(original.getAttributes());
		assertThat(loaded.<String>getAttribute("registration_id")).isEqualTo("google");
		assertThat(loaded.getAuthorizationRequestUri()).isEqualTo(original.getAuthorizationRequestUri());
		assertThat(loaded.getGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
		assertThat(loaded.getResponseType()).isEqualTo(OAuth2AuthorizationResponseType.CODE);
	}

	@Test
	@DisplayName("정상 인가 요청 쿠키 값은 3KB 이하이다")
	void keepCookieValueWithinLimit() {
		repository.saveAuthorizationRequest(createAuthorizationRequest(), request, response);

		assertThat(getSetCookieValue(AUTHORIZATION_REQUEST_COOKIE_NAME).length()).isLessThanOrEqualTo(3072);
	}

	@Test
	@DisplayName("쿠키 속성은 HttpOnly, Path=/, Max-Age=180, SameSite=Lax이며 HTTP에서는 Secure가 없다")
	void setCookieAttributes() {
		request.setParameter("redirect_uri", "http://localhost/auth/callback");

		repository.saveAuthorizationRequest(createAuthorizationRequest(), request, response);

		for (String name : List.of(AUTHORIZATION_REQUEST_COOKIE_NAME, REDIRECT_URI_COOKIE_NAME)) {
			String header = findSetCookie(name).orElseThrow();
			assertThat(header).contains("HttpOnly", "Path=/", "Max-Age=180", "SameSite=Lax");
			assertThat(header).doesNotContain("Secure");
		}
	}

	@Test
	@DisplayName("요청이 secure일 때만 Secure 속성이 붙는다")
	void setSecureAttributeOnlyOnSecureRequest() {
		request.setSecure(true);
		request.setParameter("redirect_uri", "http://localhost/auth/callback");

		repository.saveAuthorizationRequest(createAuthorizationRequest(), request, response);

		assertThat(findSetCookie(AUTHORIZATION_REQUEST_COOKIE_NAME).orElseThrow()).contains("Secure");
		assertThat(findSetCookie(REDIRECT_URI_COOKIE_NAME).orElseThrow()).contains("Secure");
	}

	@Test
	@DisplayName("null을 저장하면 두 쿠키 모두 Max-Age=0으로 삭제된다")
	void deleteBothCookiesOnSaveNull() {
		repository.saveAuthorizationRequest(null, request, response);

		assertThat(findSetCookie(AUTHORIZATION_REQUEST_COOKIE_NAME).orElseThrow()).contains("Max-Age=0");
		assertThat(findSetCookie(REDIRECT_URI_COOKIE_NAME).orElseThrow()).contains("Max-Age=0");
	}

	@Test
	@DisplayName("removeAuthorizationRequestCookies는 두 쿠키 모두 Max-Age=0으로 삭제한다")
	void deleteBothCookiesOnRemoveCookies() {
		repository.removeAuthorizationRequestCookies(request, response);

		assertThat(findSetCookie(AUTHORIZATION_REQUEST_COOKIE_NAME).orElseThrow())
				.contains("Max-Age=0", "Path=/", "HttpOnly");
		assertThat(findSetCookie(REDIRECT_URI_COOKIE_NAME).orElseThrow())
				.contains("Max-Age=0", "Path=/", "HttpOnly");
	}

	@Test
	@DisplayName("removeAuthorizationRequest는 요청을 반환하고 인가 요청 쿠키만 삭제한다")
	void removeOnlyAuthorizationRequestCookie() {
		repository.saveAuthorizationRequest(createAuthorizationRequest(), request, response);
		MockHttpServletRequest callback = requestWithCookie(
				AUTHORIZATION_REQUEST_COOKIE_NAME, getSetCookieValue(AUTHORIZATION_REQUEST_COOKIE_NAME));
		MockHttpServletResponse callbackResponse = new MockHttpServletResponse();

		OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(callback, callbackResponse);

		assertThat(removed).isNotNull();
		assertThat(removed.getState()).isEqualTo("state-value-123");
		List<String> headers = callbackResponse.getHeaders("Set-Cookie");
		assertThat(headers).hasSize(1);
		assertThat(headers.get(0)).startsWith(AUTHORIZATION_REQUEST_COOKIE_NAME + "=").contains("Max-Age=0");
	}

	@Test
	@DisplayName("쿠키가 없으면 null을 반환한다")
	void returnNullWithoutCookie() {
		assertThat(repository.loadAuthorizationRequest(request)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"!!!not-base64!!!",
			"bm90LWpzb24",
			"e30",
			"eyJhdXRob3JpemF0aW9uVXJpIjoiaHR0cHM6Ly9hLmNvbSIsImNsaWVudElkIjoiYyJ9",
			"eyJhdXRob3JpemF0aW9uVXJpIjoiIiwiY2xpZW50SWQiOiJjIiwic3RhdGUiOiJzIn0",
			"eyJjbGllbnRJZCI6ImMiLCJzdGF0ZSI6InMifQ"
	})
	@DisplayName("잘못된 Base64, JSON 아님, 필수 필드 누락 쿠키는 예외 없이 null을 반환한다")
	void returnNullOnTamperedCookie(String tamperedValue) {
		MockHttpServletRequest tampered = requestWithCookie(AUTHORIZATION_REQUEST_COOKIE_NAME, tamperedValue);

		assertThat(repository.loadAuthorizationRequest(tampered)).isNull();
	}

	@Test
	@DisplayName("허용 크기(3072자)를 초과한 쿠키는 null을 반환한다")
	void returnNullOnOversizedCookie() {
		MockHttpServletRequest oversized = requestWithCookie(
				AUTHORIZATION_REQUEST_COOKIE_NAME, "A".repeat(3073));

		assertThat(repository.loadAuthorizationRequest(oversized)).isNull();
	}

	@Test
	@DisplayName("Java 직렬화 스트림을 쿠키로 넣어도 역직렬화하지 않고 null을 반환한다")
	void returnNullOnJavaSerializedCookie() throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ObjectOutputStream stream = new ObjectOutputStream(buffer)) {
			stream.writeObject(new java.util.ArrayList<>(List.of("payload")));
		}
		String serialized = encodeBase64Url(buffer.toByteArray());
		assertThat(serialized).startsWith("rO0AB");

		MockHttpServletRequest tampered = requestWithCookie(AUTHORIZATION_REQUEST_COOKIE_NAME, serialized);

		assertThat(repository.loadAuthorizationRequest(tampered)).isNull();
	}

	@Test
	@DisplayName("정상 redirect_uri는 Base64URL로 저장되고 원문으로 복원된다")
	void restoreRedirectUri() {
		String redirectUri = "musicapp://oauth2/callback";
		request.setParameter("redirect_uri", redirectUri);

		repository.saveAuthorizationRequest(createAuthorizationRequest(), request, response);

		String cookieValue = getSetCookieValue(REDIRECT_URI_COOKIE_NAME);
		assertThat(cookieValue).isEqualTo(encodeBase64Url(redirectUri));
		assertThat(repository.loadRedirectUri(requestWithCookie(REDIRECT_URI_COOKIE_NAME, cookieValue)))
				.contains(redirectUri);
	}

	@Test
	@DisplayName("redirect_uri 파라미터가 없으면 값이 있는 쿠키를 만들지 않는다")
	void skipRedirectUriCookieWhenMissing() {
		repository.saveAuthorizationRequest(createAuthorizationRequest(), request, response);

		assertThat(findSetCookie(AUTHORIZATION_REQUEST_COOKIE_NAME)).isPresent();
		// 이전 시도의 잔존 쿠키가 재사용되지 않도록 삭제 지시(Max-Age=0)만 존재한다
		assertThat(findSetCookie(REDIRECT_URI_COOKIE_NAME).orElseThrow()).contains("Max-Age=0");
		assertThat(getSetCookieValue(REDIRECT_URI_COOKIE_NAME)).isEmpty();
	}

	@Test
	@DisplayName("redirect_uri가 빈 값이거나 512자를 초과하면 쿠키를 만들지 않는다")
	void skipRedirectUriCookieWhenBlankOrTooLong() {
		for (String invalid : List.of("", "   ", "http://localhost/" + "a".repeat(512))) {
			MockHttpServletRequest invalidRequest = new MockHttpServletRequest();
			invalidRequest.setParameter("redirect_uri", invalid);
			MockHttpServletResponse invalidResponse = new MockHttpServletResponse();

			repository.saveAuthorizationRequest(createAuthorizationRequest(), invalidRequest, invalidResponse);

			String header = invalidResponse.getHeaders("Set-Cookie").stream()
					.filter(value -> value.startsWith(REDIRECT_URI_COOKIE_NAME + "="))
					.findFirst().orElseThrow();
			assertThat(header).startsWith(REDIRECT_URI_COOKIE_NAME + "=;").contains("Max-Age=0");
		}
	}

	@Test
	@DisplayName("CR/LF·세미콜론·따옴표가 섞인 redirect_uri도 헤더 인젝션 없이 왕복 복원된다")
	void preventHeaderInjectionInRedirectUri() {
		String malicious = "http://a.com/x\r\nSet-Cookie: evil=1; Path=/;\"q\"";
		request.setParameter("redirect_uri", malicious);

		repository.saveAuthorizationRequest(createAuthorizationRequest(), request, response);

		for (String header : response.getHeaders("Set-Cookie")) {
			assertThat(header).doesNotContain("\r").doesNotContain("\n");
			assertThat(header).doesNotContain("evil=1");
		}
		String cookieValue = getSetCookieValue(REDIRECT_URI_COOKIE_NAME);
		assertThat(repository.loadRedirectUri(requestWithCookie(REDIRECT_URI_COOKIE_NAME, cookieValue)))
				.contains(malicious);
	}

	@Test
	@DisplayName("손상된 redirect_uri 쿠키는 빈 값을 반환한다")
	void returnEmptyOnTamperedRedirectUriCookie() {
		assertThat(repository.loadRedirectUri(requestWithCookie(REDIRECT_URI_COOKIE_NAME, "!!!bad!!!"))).isEmpty();
		assertThat(repository.loadRedirectUri(new MockHttpServletRequest())).isEmpty();
	}
}
