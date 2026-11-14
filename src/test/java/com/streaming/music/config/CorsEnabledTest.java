package com.streaming.music.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import com.streaming.music.security.CustomOidcUserService;
import com.streaming.music.security.JwtTokenProvider;
import com.streaming.music.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 허용 오리진이 두 개 설정된 상태의 CORS 동작을 검증한다.
 */
@WebMvcTest(controllers = SecurityTestController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class})
@ActiveProfiles({"local", "test"})
@TestPropertySource(properties = "app.cors.allowed-origins=https://web.example.com, http://localhost:3000")
class CorsEnabledTest {

	private static final String ALLOWED = "https://web.example.com";
	private static final String ALLOWED_SECOND = "http://localhost:3000";
	private static final String DENIED = "https://evil.example.com";

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private CustomOidcUserService customOidcUserService;

	@Autowired
	private MockMvc mockMvc;

	private MockHttpServletResponse preflight(String path, String origin, String method, String headers)
			throws Exception {
		MockHttpServletRequestBuilder request = options(path)
				.header("Origin", origin)
				.header("Access-Control-Request-Method", method);
		if (headers != null) {
			request.header("Access-Control-Request-Headers", headers);
		}
		return mockMvc.perform(request).andReturn().getResponse();
	}

	@Test
	@DisplayName("허용 오리진의 프리플라이트는 인증 없이 CORS 응답으로 끝난다")
	void allowPreflightFromAllowedOrigin() throws Exception {
		MockHttpServletResponse response = preflight("/api/auth/me", ALLOWED, "GET", "authorization");

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
		assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("GET", "POST", "OPTIONS");
		assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("authorization");
		assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
		assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("3600");
	}

	@Test
	@DisplayName("쉼표로 구분한 두 번째 오리진도 허용된다")
	void allowPreflightFromSecondOrigin() throws Exception {
		MockHttpServletResponse response = preflight("/api/auth/me", ALLOWED_SECOND, "POST", "content-type");

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED_SECOND);
	}

	@Test
	@DisplayName("목록에 없는 오리진의 프리플라이트는 403으로 거부된다")
	void denyPreflightFromUnknownOrigin() throws Exception {
		MockHttpServletResponse response = preflight("/api/auth/me", DENIED, "GET", "authorization");

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
	}

	@Test
	@DisplayName("목록에 없는 오리진의 일반 GET은 403으로 거부되고 허용 헤더가 없다")
	void denyActualRequestFromUnknownOrigin() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get("/test/ping").header("Origin", DENIED))
				.andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
		assertThat(response.getContentAsString()).doesNotContain("pong");
	}

	@Test
	@DisplayName("허용 오리진의 미인증 GET /api/auth/me는 401이며 Allow-Origin이 함께 붙는다")
	void attachAllowOriginTo401Response() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get("/api/auth/me").header("Origin", ALLOWED))
				.andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
		assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
	}

	@Test
	@DisplayName("허용되지 않은 메서드(DELETE) 프리플라이트는 거부된다")
	void denyPreflightWithDisallowedMethod() throws Exception {
		MockHttpServletResponse response = preflight("/api/auth/me", ALLOWED, "DELETE", null);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
	}

	@Test
	@DisplayName("허용되지 않은 헤더(X-Custom) 프리플라이트는 거부된다")
	void denyPreflightWithDisallowedHeader() throws Exception {
		MockHttpServletResponse response = preflight("/api/auth/me", ALLOWED, "GET", "x-custom");

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
	}

	@Test
	@DisplayName("오리진의 대소문자·끝 슬래시 변형 매칭 결과를 확인한다")
	void checkOriginVariantMatching() throws Exception {
		// 확인된 Spring 동작: 오리진 비교는 대소문자를 구분하지 않고 요청 오리진의 끝 슬래시를 무시한다.
		MockHttpServletResponse upperCase = preflight("/api/auth/me", "https://WEB.example.com", "GET", null);
		MockHttpServletResponse trailingSlash = preflight("/api/auth/me", "https://web.example.com/", "GET", null);

		assertThat(upperCase.getStatus()).isEqualTo(200);
		assertThat(upperCase.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://WEB.example.com");
		assertThat(trailingSlash.getStatus()).isEqualTo(200);
		assertThat(trailingSlash.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://web.example.com/");
	}
}
