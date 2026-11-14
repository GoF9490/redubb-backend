package com.streaming.music.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streaming.music.global.config.SecurityConfig;
import com.streaming.music.global.properties.JwtProperties;
import com.streaming.music.entity.User;
import com.streaming.music.global.exception.GlobalExceptionHandler;
import com.streaming.music.global.exception.UnauthorizedException;
import com.streaming.music.global.security.CustomOidcUserService;
import com.streaming.music.global.security.JwtTokenProvider;
import com.streaming.music.global.security.RestAuthenticationEntryPoint;
import com.streaming.music.service.MediaUrlResolver;
import com.streaming.music.service.TrackService;
import com.streaming.music.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JWT 필터 + EntryPoint + /api/auth/me 통합 검증. 토큰은 실제 JwtTokenProvider(테스트 키)로 생성한다.
 */
@WebMvcTest(controllers = {AuthController.class, TrackController.class})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, GlobalExceptionHandler.class,
		AuthControllerTest.JwtTestConfig.class})
@ActiveProfiles({"local", "test"})
class AuthControllerTest {

	private static final String KEY = "test-secret-key-test-secret-key-0123456789";
	private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

	@TestConfiguration
	static class JwtTestConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		@org.springframework.context.annotation.Primary
		JwtProperties jwtProperties() {
			// RedirectUriPolicy 기동 검증을 통과하는 유효한 redirect 설정이 필요하다
			return new JwtProperties(KEY, 3_600_000L, "http://localhost/auth/callback",
					List.of("http://localhost/auth/callback", "musicapp://oauth2/callback"));
		}

		@Bean
		JwtTokenProvider jwtTokenProvider(JwtProperties properties, Clock clock) {
			return new JwtTokenProvider(properties, clock);
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JsonMapper jsonMapper;

	@MockitoBean
	private UserService userService;

	@MockitoBean
	private CustomOidcUserService customOidcUserService;

	@MockitoBean
	private TrackService trackService;

	@MockitoBean
	private MediaUrlResolver mediaUrlResolver;

	private String forgedToken() {
		return new JwtTokenProvider(
				new JwtProperties("other-secret-key-other-secret-key-0123456789", 3_600_000L, null, null),
				Clock.fixed(NOW, ZoneOffset.UTC)).createToken(1L, "ROLE_USER");
	}

	private String expiredToken() {
		return new JwtTokenProvider(new JwtProperties(KEY, 1_000L, null, null),
				Clock.fixed(NOW.minusSeconds(3600), ZoneOffset.UTC)).createToken(1L, "ROLE_USER");
	}

	private void assertUnauthorizedJson(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		// MVC 핸들러는 application/json(JSON은 기본 UTF-8), EntryPoint는 charset을 명시한다
		assertThat(result.getResponse().getContentType()).startsWith("application/json");
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
		assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
		assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.noneMatch(cookie -> cookie.contains("JSESSIONID"));

		JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "timestamp");
		assertThat(body.get("code").asString()).isEqualTo("UNAUTHORIZED");
		assertThat(body.get("message").asString()).isEqualTo("인증이 필요합니다.");
		assertThat(body.get("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00");
	}

	@Test
	@DisplayName("유효 토큰이면 200과 UserResponse 4개 필드만 반환한다")
	void returnUserForValidToken() throws Exception {
		User user = User.builder().provider("GOOGLE").providerId("sub-secret")
				.email("a@example.com").profileImageUrl("http://pic/a.png").role("ROLE_USER")
				.createdAt(NOW).lastLoginAt(NOW).build();
		// 빌더에 userId가 없어 리플렉션으로 설정한다
		org.springframework.test.util.ReflectionTestUtils.setField(user, "userId", 5L);
		when(userService.getUser(5L)).thenReturn(user);
		String token = jwtTokenProvider.createToken(5L, "ROLE_USER");

		MvcResult result = mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(5))
				.andExpect(jsonPath("$.email").value("a@example.com"))
				.andExpect(jsonPath("$.profileImageUrl").value("http://pic/a.png"))
				.andExpect(jsonPath("$.role").value("ROLE_USER"))
				.andReturn();

		JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("userId", "email", "profileImageUrl", "role");
	}

	@Test
	@DisplayName("토큰 없음, 위조, 만료, 형식 오류, Basic 헤더는 /api/auth/me에서 401 JSON이다")
	void rejectMeWithoutValidToken() throws Exception {
		for (String header : new String[]{null, "Bearer " + forgedToken(), "Bearer " + expiredToken(),
				"Bearer not-a-jwt", "Bearer ", "Basic dXNlcjpwYXNz"}) {
			var request = get("/api/auth/me");
			if (header != null) {
				request = request.header(HttpHeaders.AUTHORIZATION, header);
			}
			MvcResult result = mockMvc.perform(request).andReturn();
			assertUnauthorizedJson(result);
			assertThat(result.getResponse().getContentType()).isEqualTo("application/json;charset=UTF-8");
		}
	}

	@Test
	@DisplayName("유효 토큰이지만 회원이 없으면 401 JSON이며 EntryPoint 응답과 같은 형태다")
	void rejectMeWhenUserMissing() throws Exception {
		when(userService.getUser(9L)).thenThrow(new UnauthorizedException());
		String token = jwtTokenProvider.createToken(9L, "ROLE_USER");

		MvcResult handlerResult = mockMvc.perform(get("/api/auth/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
		MvcResult entryPointResult = mockMvc.perform(get("/api/auth/me")).andReturn();

		assertUnauthorizedJson(handlerResult);
		assertUnauthorizedJson(entryPointResult);
		JsonNode a = jsonMapper.readTree(handlerResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		JsonNode b = jsonMapper.readTree(entryPointResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(Set.copyOf(a.propertyNames())).isEqualTo(Set.copyOf(b.propertyNames()));
		assertThat(a.get("code")).isEqualTo(b.get("code"));
		assertThat(a.get("message")).isEqualTo(b.get("message"));
	}

	@Test
	@DisplayName("같은 스레드의 연속 요청에서 이전 요청의 인증이 남지 않는다")
	void notLeakAuthenticationBetweenRequests() throws Exception {
		User user = User.builder().provider("GOOGLE").providerId("sub-leak")
				.email("a@example.com").role("ROLE_USER").createdAt(NOW).lastLoginAt(NOW).build();
		org.springframework.test.util.ReflectionTestUtils.setField(user, "userId", 5L);
		when(userService.getUser(5L)).thenReturn(user);
		String token = jwtTokenProvider.createToken(5L, "ROLE_USER");

		mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
		// 같은 스레드에서 요청이 끝난 직후 SecurityContextHolder가 비어 있어야 한다
		assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext()
				.getAuthentication()).isNull();

		assertUnauthorizedJson(mockMvc.perform(get("/api/auth/me")).andReturn());
	}

	@Test
	@DisplayName("무효 토큰·Basic 헤더로도 /api/tracks는 차단되지 않고 컨트롤러까지 도달해 200이다")
	void notBlockTracksWithInvalidToken() throws Exception {
		when(trackService.getTracks(any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
		List<String> headers = List.of("Bearer " + forgedToken(), "Bearer " + expiredToken(),
				"Bearer not-a-jwt", "Basic dXNlcjpwYXNz");

		for (String header : headers) {
			mockMvc.perform(get("/api/tracks").header(HttpHeaders.AUTHORIZATION, header))
					.andExpect(status().isOk());
		}
		verify(trackService, org.mockito.Mockito.times(headers.size()))
				.getTracks(any(), any(), anyInt(), anyInt());
	}
}
