package com.streaming.music.config.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS 설정 (app.cors.*). allowedOrigins가 비어 있으면 CORS 비활성을 의미한다.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = PropertyUtils.normalizeList(allowedOrigins);
	}
}
