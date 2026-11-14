package com.streaming.music.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비동기 처리 설정 (app.async.*, PRD-05 §4.2).
 */
@ConfigurationProperties(prefix = "app.async")
public record AsyncProperties(@DefaultValue Transcode transcode) {

	/**
	 * 변환 전용 스레드 풀(transcodeExecutor) 크기 설정.
	 */
	public record Transcode(
			@DefaultValue("1") int corePoolSize,
			@DefaultValue("2") int maxPoolSize,
			@DefaultValue("50") int queueCapacity
	) {
	}
}
