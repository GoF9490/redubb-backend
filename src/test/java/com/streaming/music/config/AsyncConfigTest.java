package com.streaming.music.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AsyncConfigTest {

	@Test
	@DisplayName("transcodeExecutor는 core 1, max 2, queue 50, 스레드 접두사 transcode-로 구성된다")
	void createsTranscodeExecutorWithConfiguredSizes() {
		ThreadPoolTaskExecutor executor = new AsyncConfig().transcodeExecutor();
		executor.initialize();
		try {
			assertThat(executor.getCorePoolSize()).isEqualTo(1);
			assertThat(executor.getMaxPoolSize()).isEqualTo(2);
			assertThat(executor.getQueueCapacity()).isEqualTo(50);
			assertThat(executor.getThreadNamePrefix()).isEqualTo("transcode-");
		} finally {
			executor.shutdown();
		}
	}

	@Test
	@DisplayName("거부 정책은 기본 AbortPolicy를 유지한다")
	void keepsAbortPolicyAsRejectionHandler() {
		ThreadPoolTaskExecutor executor = new AsyncConfig().transcodeExecutor();
		executor.initialize();
		try {
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
					.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
		} finally {
			executor.shutdown();
		}
	}
}
