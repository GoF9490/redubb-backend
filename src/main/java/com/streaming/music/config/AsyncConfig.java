package com.streaming.music.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 HLS 변환 전용 스레드 풀 설정 (PRD-05 §6.3-1).
 * <p>
 * FFmpeg가 백엔드 CPU를 독점하지 못하도록 동시 변환 수를 core 1 / max 2 / queue 50으로 제한한다.
 * 거부 정책은 기본 {@code AbortPolicy}를 유지한다. 거부된 Runnable에서는 trackId를 알 수 없으므로
 * 거부 처리는 trackId를 아는 호출부 {@code TranscodeEventRelay}가 {@code TaskRejectedException}을 잡아
 * FAILED(TRANSCODE_QUEUE_FULL)로 기록하는 방식으로 수행한다.
 * <p>
 * 종료 정책: PRD에 지정이 없어 기본값(종료 시 실행 중 작업 인터럽트)을 유지한다.
 * 인터럽트된 변환은 T4-3이 FAILED로 처리하고, 유실된 PENDING/PROCESSING은 T4-4 기동 복구가 보완한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	/** 변환 전용 executor 빈 이름. {@code @Async("transcodeExecutor")}에서 참조한다. */
	public static final String TRANSCODE_EXECUTOR = "transcodeExecutor";

	public static final int CORE_POOL_SIZE = 1;
	public static final int MAX_POOL_SIZE = 2;
	public static final int QUEUE_CAPACITY = 50;
	public static final String THREAD_NAME_PREFIX = "transcode-";

	@Bean(name = TRANSCODE_EXECUTOR)
	public ThreadPoolTaskExecutor transcodeExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(CORE_POOL_SIZE);
		executor.setMaxPoolSize(MAX_POOL_SIZE);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
		return executor;
	}
}
