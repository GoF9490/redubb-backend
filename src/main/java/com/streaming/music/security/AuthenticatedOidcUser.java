package com.streaming.music.security;

import java.util.Collection;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * OAuth2(OIDC) 로그인 성공 후 Authentication의 principal.
 * DB 회원의 userId와 role을 담아 성공 핸들러가 JWT를 발급할 수 있게 한다.
 * 클레임은 sub/email/picture만 포함한다.
 */
@Getter
public class AuthenticatedOidcUser extends DefaultOidcUser {

    private final Long userId;
    private final String role;

    public AuthenticatedOidcUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken,
            Long userId, String role) {
        super(authorities, idToken, null, "sub");
        this.userId = userId;
        this.role = role;
    }
}
