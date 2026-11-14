package com.streaming.music.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 미디어 공개 URL 설정 (app.media.*). 빈 값이면 상대경로("/stream/...")를 의미한다.
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(String publicBaseUrl) {

	public MediaProperties {
		publicBaseUrl = PropertyUtils.normalizeText(publicBaseUrl);
	}
}
