package com.streaming.music.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.streaming.music.controller.AuthController;
import com.streaming.music.exception.GlobalExceptionHandler;
import com.streaming.music.security.CustomOidcUserService;
import com.streaming.music.security.JwtTokenProvider;
import com.streaming.music.security.RestAuthenticationEntryPoint;
import com.streaming.music.service.UserService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * oauth2Login 연동 검증(실제 Google 호출 없음). 콜백 성공 처리는 핸들러 단위 테스트가 담당한다.
 */
@WebMvcTest(controllers = {SecurityTestController.class, AuthController.class})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
@ActiveProfiles({"local", "test"})
class OAuth2LoginFlowTest {

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

    @MockitoBean
    private UserService userService;

    @Autowired
    private MockMvc mockMvc;

    private List<String> setCookies(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie");
    }

    @Test
    @DisplayName("로그인 진입은 Google 인가 URL로 302하고 state·nonce를 포함하며 세션 쿠키가 없다")
    void redirectToGoogleAuthorization() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google")
                .param("redirect_uri", "musicapp://oauth2/callback")).andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(location).contains("state=", "nonce=", "client_id=test-google-client-id", "scope=openid");
        assertThat(location).contains("redirect_uri=http", "login/oauth2/code/google");

        assertThat(setCookies(result)).anyMatch(c -> c.startsWith("oauth2_auth_request=")
                && c.contains("HttpOnly") && c.contains("SameSite=Lax"));
        assertThat(setCookies(result)).anyMatch(c -> c.startsWith("oauth2_redirect_uri=")
                && !c.startsWith("oauth2_redirect_uri=;") && c.contains("HttpOnly"));
        assertThat(setCookies(result)).noneMatch(c -> c.contains("JSESSIONID"));
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("redirect_uri 없이 진입하면 인가 요청 쿠키만 값이 있고 세션이 없다")
    void startLoginWithoutRedirectUri() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(setCookies(result)).anyMatch(c -> c.startsWith("oauth2_auth_request=") && !c.startsWith("oauth2_auth_request=;"));
        assertThat(setCookies(result)).noneMatch(c -> c.contains("JSESSIONID"));
    }

    @Test
    @DisplayName("oauth2Login 추가 후에도 미인증 /api/auth/me는 리다이렉트 없이 401 JSON이다")
    void returnUnauthorizedJsonForMeWithoutRedirect() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader("Location")).isNull();
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getContentAsString()).contains("UNAUTHORIZED");
        assertThat(setCookies(result)).noneMatch(c -> c.contains("JSESSIONID"));
    }

    @Test
    @DisplayName("인가 요청 쿠키 없는 콜백은 실패 핸들러로 연결되어 기본 URI?error= 로 리다이렉트한다")
    void redirectWithErrorOnCallbackWithoutAuthorizationRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/login/oauth2/code/google")
                .param("code", "x").param("state", "y")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost/auth/callback?error=authorization_request_not_found");
        assertThat(setCookies(result)).anyMatch(c -> c.startsWith("oauth2_redirect_uri=;") && c.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("기본 로그인 페이지(HTML)는 노출되지 않는다")
    void notExposeDefaultLoginPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/login")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).doesNotContain("html");
    }
}
