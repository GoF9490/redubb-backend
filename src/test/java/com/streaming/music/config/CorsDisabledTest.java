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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * app.cors.allowed-origins가 비어 있는(기본) 상태에서 CORS가 적용되지 않음을 검증한다.
 */
@WebMvcTest(controllers = SecurityTestController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class})
@ActiveProfiles({"local", "test"})
class CorsDisabledTest {

	private static final String ORIGIN = "https://web.example.com";

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private CustomOidcUserService customOidcUserService;

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("빈 설정에서는 프리플라이트 응답에 Allow-Origin이 없다")
	void omitAllowOriginOnPreflight() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(options("/api/auth/me")
						.header("Origin", ORIGIN)
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "authorization"))
				.andReturn().getResponse();

		// 확인된 동작: CorsFilter가 통과시키고 MVC가 OPTIONS를 처리하여 200이 된다(403 아님).
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
		assertThat(response.getHeader("Access-Control-Allow-Methods")).isNull();
	}

	@Test
	@DisplayName("빈 설정에서는 일반 요청에도 Allow-Origin이 없고 본문은 그대로 온다")
	void omitAllowOriginOnActualRequest() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get("/test/ping").header("Origin", ORIGIN))
				.andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).isEqualTo("pong");
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
	}
}
