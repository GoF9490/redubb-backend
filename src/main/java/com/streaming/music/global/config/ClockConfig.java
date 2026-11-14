package com.streaming.music.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 의존 서비스에 주입할 공용 Clock 빈. 테스트에서는 고정 Clock으로 대체한다.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
