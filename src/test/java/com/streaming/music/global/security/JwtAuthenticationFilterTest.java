package com.streaming.music.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.global.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

	private static final String KEY = "test-secret-key-test-secret-key-0123456789";
	private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

	private JwtTokenProvider provider;
	private JwtAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		provider = new JwtTokenProvider(new JwtProperties(KEY, 3_600_000L, null, null),
				Clock.fixed(NOW, ZoneOffset.UTC));
		filter = new JwtAuthenticationFilter(provider);
		SecurityContextHolder.clearContext();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private Authentication runFilter(String authorization, MockFilterChain chain) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (authorization != null) {
			request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		// 체인 안에서 보이는 인증 정보를 캡처한다
		Authentication[] holder = new Authentication[1];
		FilterChain capturing = (req, res) -> {
			holder[0] = SecurityContextHolder.getContext().getAuthentication();
			chain.doFilter(req, res);
		};
		filter.doFilter(request, response, capturing);
		assertThat(chain.getRequest()).as("체인은 항상 호출되어야 한다").isNotNull();
		assertThat(response.getStatus()).isEqualTo(200);
		return holder[0];
	}

	@Test
	@DisplayName("유효 토큰은 AuthenticatedUser principal과 role 권한을 설정한다")
	void setAuthenticationForValidToken() throws Exception {
		String token = provider.createToken(7L, "ROLE_USER");

		Authentication auth = runFilter("Bearer " + token, new MockFilterChain());

		assertThat(auth).isNotNull();
		assertThat(auth.getPrincipal()).isEqualTo(new AuthenticatedUser(7L, "ROLE_USER"));
		assertThat(auth.getCredentials()).isNull();
		assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
	}

	@Test
	@DisplayName("Bearer 접두는 대소문자를 구분하지 않고 토큰 앞뒤 공백은 무시한다")
	void acceptCaseInsensitiveBearerPrefix() throws Exception {
		String token = provider.createToken(7L, "ROLE_USER");

		assertThat(runFilter("bearer " + token + "  ", new MockFilterChain())).isNotNull();
		assertThat(runFilter("BEARER  " + token, new MockFilterChain())).isNotNull();
	}

	@Test
	@DisplayName("헤더 없음, Basic, 빈 토큰, 형식 오류 토큰은 익명으로 통과한다")
	void passAnonymousForInvalidHeaders() throws Exception {
		assertThat(runFilter(null, new MockFilterChain())).isNull();
		assertThat(runFilter("Basic dXNlcjpwYXNz", new MockFilterChain())).isNull();
		assertThat(runFilter("Bearer ", new MockFilterChain())).isNull();
		assertThat(runFilter("Bearer    ", new MockFilterChain())).isNull();
		assertThat(runFilter("Bearer not-a-jwt", new MockFilterChain())).isNull();
		assertThat(runFilter("Bearer", new MockFilterChain())).isNull();
	}

	@Test
	@DisplayName("위조·만료 토큰은 익명으로 통과한다")
	void passAnonymousForForgedOrExpiredToken() throws Exception {
		JwtTokenProvider otherKey = new JwtTokenProvider(
				new JwtProperties("other-secret-key-other-secret-key-0123456789", 3_600_000L, null, null),
				Clock.fixed(NOW, ZoneOffset.UTC));
		JwtTokenProvider past = new JwtTokenProvider(new JwtProperties(KEY, 1_000L, null, null),
				Clock.fixed(NOW.minusSeconds(3600), ZoneOffset.UTC));

		assertThat(runFilter("Bearer " + otherKey.createToken(1L, "ROLE_USER"), new MockFilterChain())).isNull();
		assertThat(runFilter("Bearer " + past.createToken(1L, "ROLE_USER"), new MockFilterChain())).isNull();
	}

	@Test
	@DisplayName("이미 인증 정보가 있으면 덮어쓰지 않는다")
	void keepExistingAuthentication() throws Exception {
		Authentication existing = new org.springframework.security.authentication
				.UsernamePasswordAuthenticationToken("existing", null, java.util.List.of());
		SecurityContextHolder.getContext().setAuthentication(existing);
		String token = provider.createToken(7L, "ROLE_USER");

		assertThat(runFilter("Bearer " + token, new MockFilterChain())).isSameAs(existing);
	}
}
