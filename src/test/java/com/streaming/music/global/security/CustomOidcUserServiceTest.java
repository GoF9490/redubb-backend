package com.streaming.music.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.music.entity.User;
import com.streaming.music.service.OAuth2UserInfo;
import com.streaming.music.service.UserService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CustomOidcUserServiceTest {

	private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant EXPIRES = Instant.parse("2026-01-01T01:00:00Z");

	@Mock
	private UserService userService;

	@Mock
	private OAuth2UserService<OidcUserRequest, OidcUser> delegate;

	private CustomOidcUserService service;
	private OidcUserRequest userRequest;

	@BeforeEach
	void setUp() {
		service = new CustomOidcUserService(userService, delegate);
		ClientRegistration registration = ClientRegistration.withRegistrationId("google")
				.clientId("client-id")
				.clientSecret("client-secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost/login/oauth2/code/google")
				.authorizationUri("https://accounts.example/auth")
				.tokenUri("https://accounts.example/token")
				.build();
		OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token",
				ISSUED, EXPIRES);
		OidcIdToken idToken = new OidcIdToken("id-token-value", ISSUED, EXPIRES, Map.of("sub", "sub-1"));
		userRequest = new OidcUserRequest(registration, accessToken, idToken);
	}

	private Map<String, Object> baseClaims() {
		Map<String, Object> claims = new HashMap<>();
		claims.put("sub", "sub-1");
		claims.put("email", "user@example.com");
		claims.put("picture", "http://pic/1.png");
		claims.put("name", "홍길동");
		claims.put("given_name", "길동");
		claims.put("family_name", "홍");
		claims.put("email_verified", true);
		return claims;
	}

	/** 위임 객체가 claims를 가진 OidcUser를 반환하도록 스텁한다. */
	private void stubDelegate(Map<String, Object> claims) {
		OidcIdToken idToken = new OidcIdToken("id-token-value", ISSUED, EXPIRES, claims);
		OidcUser delegated = new DefaultOidcUser(List.<GrantedAuthority>of(), idToken, (OidcUserInfo) null);
		when(delegate.loadUser(userRequest)).thenReturn(delegated);
	}

	private User createUser(Long userId, String role) {
		User user = User.builder().providerId("sub-1").email("user@example.com").role(role).build();
		ReflectionTestUtils.setField(user, "userId", userId);
		return user;
	}

	@Test
	@DisplayName("sub/email/picture를 매핑해 upsertUser를 호출하고 userId·role을 담은 principal을 반환한다")
	void upsertUserWithMappedClaims() {
		stubDelegate(baseClaims());
		when(userService.upsertUser(any())).thenReturn(createUser(7L, User.ROLE_USER));

		OidcUser result = service.loadUser(userRequest);

		ArgumentCaptor<OAuth2UserInfo> captor = ArgumentCaptor.forClass(OAuth2UserInfo.class);
		verify(userService).upsertUser(captor.capture());
		assertThat(captor.getValue()).isEqualTo(new OAuth2UserInfo("sub-1", "user@example.com", "http://pic/1.png"));
		assertThat(result).isInstanceOf(AuthenticatedOidcUser.class);
		AuthenticatedOidcUser principal = (AuthenticatedOidcUser) result;
		assertThat(principal.getUserId()).isEqualTo(7L);
		assertThat(principal.getRole()).isEqualTo(User.ROLE_USER);
		assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly(User.ROLE_USER);
		assertThat(principal.getName()).isEqualTo("sub-1");
		assertThat(principal.getIdToken().getTokenValue()).isEqualTo("id-token-value");
		assertThat(principal.getIdToken().getIssuedAt()).isEqualTo(ISSUED);
		assertThat(principal.getIdToken().getExpiresAt()).isEqualTo(EXPIRES);
	}

	@Test
	@DisplayName("picture가 없거나 공백이면 null로 upsert한다")
	void acceptMissingPicture() {
		Map<String, Object> claims = baseClaims();
		claims.put("picture", "  ");
		stubDelegate(claims);
		when(userService.upsertUser(any())).thenReturn(createUser(1L, User.ROLE_USER));

		OidcUser result = service.loadUser(userRequest);

		ArgumentCaptor<OAuth2UserInfo> captor = ArgumentCaptor.forClass(OAuth2UserInfo.class);
		verify(userService).upsertUser(captor.capture());
		assertThat(captor.getValue().profileImageUrl()).isNull();
		assertThat(result.getClaims()).doesNotContainKey("picture");
	}

	@Test
	@DisplayName("sub가 없으면 OAuth2AuthenticationException을 던지고 upsert하지 않는다")
	void rejectMissingSub() {
		Map<String, Object> claims = baseClaims();
		claims.remove("sub");
		OidcUser delegated = mock(OidcUser.class);
		when(delegated.getClaims()).thenReturn(claims);
		when(delegate.loadUser(userRequest)).thenReturn(delegated);

		assertThatThrownBy(() -> service.loadUser(userRequest))
				.isInstanceOfSatisfying(OAuth2AuthenticationException.class,
						ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_user_info"));
		verify(userService, never()).upsertUser(any());
	}

	@Test
	@DisplayName("email이 공백이면 OAuth2AuthenticationException을 던지고 upsert하지 않는다")
	void rejectBlankEmail() {
		Map<String, Object> claims = baseClaims();
		claims.put("email", " ");
		stubDelegate(claims);

		assertThatThrownBy(() -> service.loadUser(userRequest))
				.isInstanceOfSatisfying(OAuth2AuthenticationException.class,
						ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_user_info"));
		verify(userService, never()).upsertUser(any());
	}

	@Test
	@DisplayName("예외 메시지에 sub·email·토큰 값이 포함되지 않는다")
	void notLeakValuesInExceptionMessage() {
		Map<String, Object> claims = baseClaims();
		claims.put("email_verified", false);
		stubDelegate(claims);

		assertThatThrownBy(() -> service.loadUser(userRequest))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.satisfies(ex -> assertThat(ex.getMessage())
						.doesNotContain("sub-1", "user@example.com", "id-token-value", "access-token"));
	}

	@Test
	@DisplayName("email_verified가 Boolean false 또는 문자열 false이면 거부한다")
	void rejectUnverifiedEmail() {
		for (Object unverified : new Object[] {false, "false", "FALSE"}) {
			Map<String, Object> claims = baseClaims();
			claims.put("email_verified", unverified);
			stubDelegate(claims);

			assertThatThrownBy(() -> service.loadUser(userRequest))
					.isInstanceOf(OAuth2AuthenticationException.class);
		}
		verify(userService, never()).upsertUser(any());
	}

	@Test
	@DisplayName("email_verified가 없거나 true/문자열 true/예상 밖 타입이어도 예외 없이 통과한다")
	void acceptVerifiedOrUnknownEmailVerified() {
		when(userService.upsertUser(any())).thenReturn(createUser(1L, User.ROLE_USER));
		for (Object verified : new Object[] {true, "true", 1, null}) {
			Map<String, Object> claims = baseClaims();
			if (verified == null) {
				claims.remove("email_verified");
			} else {
				claims.put("email_verified", verified);
			}
			stubDelegate(claims);

			assertThat(service.loadUser(userRequest)).isInstanceOf(AuthenticatedOidcUser.class);
		}
	}

	@Test
	@DisplayName("name·given_name·family_name 등 불필요한 클레임은 principal에 담지 않는다")
	void notExposeUnneededClaims() {
		stubDelegate(baseClaims());
		when(userService.upsertUser(any())).thenReturn(createUser(1L, User.ROLE_USER));

		AuthenticatedOidcUser principal = (AuthenticatedOidcUser) service.loadUser(userRequest);

		assertThat(principal.getClaims()).containsOnlyKeys("sub", "email", "picture");
		assertThat(principal.getAttributes()).doesNotContainKeys("name", "given_name", "family_name");
		assertThat(principal.getIdToken().getClaims()).doesNotContainKeys("name", "given_name", "family_name");
		assertThat(principal.getUserInfo()).isNull();
	}

	@Test
	@DisplayName("위임 객체의 예외는 그대로 전파된다")
	void propagateDelegateException() {
		OAuth2AuthenticationException failure = new OAuth2AuthenticationException("invalid_user_info_response");
		when(delegate.loadUser(userRequest)).thenThrow(failure);

		assertThatThrownBy(() -> service.loadUser(userRequest)).isSameAs(failure);
		verify(userService, never()).upsertUser(any());
	}
}
