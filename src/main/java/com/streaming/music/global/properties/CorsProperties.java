package com.streaming.music.global.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CORS 설정 (app.cors.*). allowedOrigins가 비어 있으면 CORS 비활성을 의미한다.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
		List<String> allowedOrigins,
		@DefaultValue({"GET", "POST", "OPTIONS"}) List<String> allowedMethods,
		@DefaultValue({"Authorization", "Content-Type"}) List<String> allowedHeaders
) {

	public CorsProperties {
		allowedOrigins = PropertyUtils.normalizeList(allowedOrigins);
		allowedMethods = PropertyUtils.normalizeList(allowedMethods);
		allowedHeaders = PropertyUtils.normalizeList(allowedHeaders);
	}
}
