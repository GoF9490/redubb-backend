package com.streaming.music.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 음원 목록 조회 설정 (app.track.*, PRD-05 §4.2).
 */
@ConfigurationProperties(prefix = "app.track")
public record TrackProperties(
		@DefaultValue("20") int defaultPageSize,
		@DefaultValue("100") int maxPageSize
) {
}
