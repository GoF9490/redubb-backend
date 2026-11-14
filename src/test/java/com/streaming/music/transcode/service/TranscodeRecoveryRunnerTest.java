package com.streaming.music.transcode.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.music.global.properties.AudioProperties;
import com.streaming.music.repository.TrackRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class TranscodeRecoveryRunnerTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	@Mock
	private TrackRepository trackRepository;

	@Mock
	private PlatformTransactionManager transactionManager;

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any()))
				.thenReturn(new SimpleTransactionStatus());
	}

	private TranscodeRecoveryRunner createRunner(int timeoutMinutes) {
		AudioProperties properties = new AudioProperties("/tmp/w", "ffmpeg", timeoutMinutes, 6, "192k", 1L);
		return new TranscodeRecoveryRunner(trackRepository, properties, clock, transactionManager);
	}

	@Test
	@DisplayName("threshold는 now - timeout×2로 계산되어 같은 now와 함께 리포지토리에 전달된다")
	void passesThresholdCalculatedFromClock() {
		when(trackRepository.failStaleTracks(any(), any(), anyString())).thenReturn(2);

		createRunner(3).run(null);

		verify(trackRepository).failStaleTracks(
				NOW.minusSeconds(6 * 60), NOW, "INTERRUPTED_BY_RESTART");
	}

	@Test
	@DisplayName("설정된 timeout 값에 맞춰 threshold가 달라진다")
	void usesConfiguredTimeout() {
		when(trackRepository.failStaleTracks(any(), any(), anyString())).thenReturn(1);

		createRunner(5).run(null);

		verify(trackRepository).failStaleTracks(
				NOW.minusSeconds(10 * 60), NOW, "INTERRUPTED_BY_RESTART");
	}

	@Test
	@DisplayName("갱신 건수가 0건이어도 정상 종료한다")
	void completesWhenNothingUpdated() {
		when(trackRepository.failStaleTracks(any(), any(), anyString())).thenReturn(0);

		assertThatCode(() -> createRunner(3).run(null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("복구 쿼리가 예외를 던져도 기동을 막지 않고 정상 종료한다")
	void doesNotPropagateRepositoryFailure() {
		when(trackRepository.failStaleTracks(any(), any(), anyString()))
				.thenThrow(new IllegalStateException("db down"));

		assertThatCode(() -> createRunner(3).run(null)).doesNotThrowAnyException();
	}
}
