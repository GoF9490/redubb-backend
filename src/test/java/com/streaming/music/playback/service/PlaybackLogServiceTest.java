package com.streaming.music.playback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.streaming.music.playback.entity.ActionType;
import com.streaming.music.playback.entity.PlaybackLog;
import com.streaming.music.track.entity.Track;
import com.streaming.music.user.entity.User;
import com.streaming.music.playback.repository.PlaybackLogRepository;
import com.streaming.music.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class PlaybackLogServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-11-11T01:00:00Z");

	@Mock
	private PlaybackLogRepository playbackLogRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private PlatformTransactionManager transactionManager;

	private PlaybackLogService playbackLogService;
	private Track track;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
		playbackLogService = new PlaybackLogService(
				playbackLogRepository, userRepository, clock, transactionManager);
		track = Track.builder().trackId(UUID.randomUUID()).build();
	}

	@Test
	@DisplayName("로그인 사용자는 user가 설정된 이력이 저장된다")
	void recordsLogWithUserWhenLoggedIn() {
		User user = mock(User.class);
		when(userRepository.getReferenceById(1L)).thenReturn(user);

		playbackLogService.recordLog(track, 1L, ActionType.STREAM);

		ArgumentCaptor<PlaybackLog> captor = ArgumentCaptor.forClass(PlaybackLog.class);
		verify(playbackLogRepository).save(captor.capture());
		PlaybackLog saved = captor.getValue();
		assertThat(saved.getUser()).isSameAs(user);
		assertThat(saved.getTrack()).isSameAs(track);
		assertThat(saved.getActionType()).isEqualTo(ActionType.STREAM);
		assertThat(saved.getCreatedAt()).isEqualTo(FIXED_NOW);
	}

	@Test
	@DisplayName("비로그인(userId=null)이면 user=null로 저장되고 UserRepository는 호출되지 않는다")
	void recordsLogWithoutUserWhenAnonymous() {
		playbackLogService.recordLog(track, null, ActionType.DOWNLOAD);

		ArgumentCaptor<PlaybackLog> captor = ArgumentCaptor.forClass(PlaybackLog.class);
		verify(playbackLogRepository).save(captor.capture());
		assertThat(captor.getValue().getUser()).isNull();
		assertThat(captor.getValue().getActionType()).isEqualTo(ActionType.DOWNLOAD);
		verifyNoInteractions(userRepository);
	}

	@Test
	@DisplayName("리포지토리 저장이 예외를 던져도 전파하지 않는다")
	void swallowsExceptionWhenSaveFails() {
		when(playbackLogRepository.save(any(PlaybackLog.class)))
				.thenThrow(new DataIntegrityViolationException("FK 위반"));

		assertDoesNotThrow(() -> playbackLogService.recordLog(track, null, ActionType.STREAM));
	}

	@Test
	@DisplayName("사용자 참조 조회가 예외를 던져도 전파하지 않고 저장도 시도하지 않는다")
	void swallowsExceptionWhenUserLookupFails() {
		when(userRepository.getReferenceById(99L)).thenThrow(new IllegalStateException("조회 실패"));

		assertDoesNotThrow(() -> playbackLogService.recordLog(track, 99L, ActionType.STREAM));
		verifyNoInteractions(playbackLogRepository);
	}

	@Test
	@DisplayName("track 또는 actionType이 null이면 NullPointerException을 던진다")
	void throwsNpeWhenArgumentsAreNull() {
		assertThrows(NullPointerException.class,
				() -> playbackLogService.recordLog(null, 1L, ActionType.STREAM));
		assertThrows(NullPointerException.class,
				() -> playbackLogService.recordLog(track, 1L, null));
		verifyNoInteractions(playbackLogRepository);
	}
}
