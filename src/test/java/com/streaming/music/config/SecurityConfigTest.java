package com.streaming.music.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streaming.music.security.CustomOidcUserService;
import com.streaming.music.security.JwtTokenProvider;
import com.streaming.music.security.RestAuthenticationEntryPoint;
import java.util.List;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 필터 체인이 실제로 적용된 상태(addFilters 기본값)에서 SecurityConfig를 검증한다.
 */
@WebMvcTest(controllers = SecurityTestController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class})
@ActiveProfiles({"local", "test"})
class SecurityConfigTest {

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    @DisplayName("SecurityConfig의 필터 체인이 등록되어 있다")
    void registerSecurityFilterChain() {
        assertThat(filterChainProxy.getFilterChains()).isNotEmpty();
    }

    @Test
    @DisplayName("MockMvc 요청이 실제로 Security 필터를 통과한다")
    void applySecurityFilters() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ping")).andReturn();

        // Security 필터(HeaderWriterFilter)가 적용된 경우에만 붙는 헤더
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("익명 요청은 컨트롤러 응답을 반환한다")
    void allowAnonymousRequest() throws Exception {
        mockMvc.perform(get("/test/ping"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("존재하지 않는 경로도 302 로그인 리다이렉트가 없다")
    void notRedirectToLoginPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/no-such-path")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(302);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
    }

    @Test
    @DisplayName("응답에 JSESSIONID 쿠키가 없고 세션이 생성되지 않는다")
    void notIssueSessionCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ping")).andReturn();

        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).noneMatch(cookie -> cookie.contains("JSESSIONID"));
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("CSRF 토큰 없이 POST해도 403이 아니다")
    void allowPostWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/test/ping"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("HTTP Basic 인증 챌린지 헤더가 없다")
    void notRequireHttpBasic() throws Exception {
        MvcResult result = mockMvc.perform(get("/no-such-path")).andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }
}
