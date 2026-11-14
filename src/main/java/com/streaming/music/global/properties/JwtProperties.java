package com.streaming.music.global.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT 및 OAuth2 리다이렉트 설정 (app.jwt.*, PRD-05 §4.2).
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
		String secretKey,
		@DefaultValue("86400000") long accessTokenExpirationMs,
		String defaultRedirectUri,
		List<String> allowedRedirectUris
) {

	public JwtProperties {
		allowedRedirectUris = PropertyUtils.normalizeList(allowedRedirectUris);
	}

	/** 시크릿 키 로그 노출 방지를 위해 마스킹한다. */
	@Override
	public String toString() {
		return "JwtProperties[secretKey=****, accessTokenExpirationMs=" + accessTokenExpirationMs
				+ ", defaultRedirectUri=" + defaultRedirectUri
				+ ", allowedRedirectUris=" + allowedRedirectUris + "]";
	}
}
