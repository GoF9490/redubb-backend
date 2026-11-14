package com.streaming.music.global.security;

import com.streaming.music.user.entity.User;
import com.streaming.music.user.service.OAuth2UserInfo;
import com.streaming.music.user.service.UserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Google OIDC 로그인 사용자 서비스 (PRD-05 §6.1).
 *
 * <p>scope에 openid가 있으면 Spring은 일반 OAuth2UserService가 아닌 OIDC 서비스만 호출하므로
 * 이 클래스는 {@code oidcUserService(...)}에 등록해야 한다 (SecurityConfig 연동은 T6-5).
 * sub/email/picture만 추출해 회원을 Upsert하고, 이름 등 다른 클레임은 보관하지 않는다.
 * 로그와 예외 메시지에는 속성 값·토큰을 남기지 않는다.
 */
@Slf4j
@Component
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

	static final String ERROR_INVALID_USER_INFO = "invalid_user_info";

	private final UserService userService;
	private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

	/** 운영용: 기본 OidcUserService에 위임한다. */
	@Autowired
	public CustomOidcUserService(UserService userService) {
		this(userService, new OidcUserService());
	}

	/** 테스트용: 위임 객체를 주입한다. 생성자가 둘이므로 위의 @Autowired로 Spring 선택을 고정한다. */
	CustomOidcUserService(UserService userService, OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
		this.userService = userService;
		this.delegate = delegate;
	}

	@Override
	public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
		OidcUser oidcUser = delegate.loadUser(userRequest);
		Map<String, Object> claims = oidcUser.getClaims();

		String sub = getText(claims, "sub");
		String email = getText(claims, "email");
		String picture = getText(claims, "picture");

		if (sub == null) {
			throw invalidUserInfo("sub 누락");
		}
		if (email == null) {
			throw invalidUserInfo("email 누락");
		}
		if (isEmailExplicitlyUnverified(claims.get("email_verified"))) {
			throw invalidUserInfo("email 미검증");
		}

		User user = userService.upsertUser(new OAuth2UserInfo(sub, email, picture));

		// 최소 클레임(sub/email/picture)만 가진 ID 토큰으로 재구성한다
		Map<String, Object> minimalClaims = new LinkedHashMap<>();
		minimalClaims.put("sub", sub);
		minimalClaims.put("email", email);
		if (picture != null) {
			minimalClaims.put("picture", picture);
		}
		OidcIdToken original = userRequest.getIdToken();
		OidcIdToken minimalIdToken = new OidcIdToken(original.getTokenValue(), original.getIssuedAt(),
				original.getExpiresAt(), minimalClaims);

		return new AuthenticatedOidcUser(List.of(new SimpleGrantedAuthority(user.getRole())), minimalIdToken,
				user.getUserId(), user.getRole());
	}

	/** 문자열이 아니거나 공백이면 null로 정규화한다. */
	private String getText(Map<String, Object> claims, String key) {
		Object value = claims.get(key);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		return null;
	}

	/** Boolean false 또는 문자열 "false"(대소문자 무시)일 때만 미검증으로 본다. 타입이 달라도 예외를 내지 않는다. */
	private boolean isEmailExplicitlyUnverified(Object value) {
		if (value instanceof Boolean flag) {
			return !flag;
		}
		return value instanceof String text && "false".equalsIgnoreCase(text.trim());
	}

	private OAuth2AuthenticationException invalidUserInfo(String reason) {
		log.warn("OIDC 사용자 정보 검증 실패: {}", reason);
		OAuth2Error error = new OAuth2Error(ERROR_INVALID_USER_INFO);
		return new OAuth2AuthenticationException(error, error.getErrorCode());
	}
}
