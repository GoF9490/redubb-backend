package com.streaming.music.transcode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.music.track.entity.Track;
import com.streaming.music.track.entity.TrackStatus;
import com.streaming.music.track.repository.TrackRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TranscodeRejectionHandlerTest {

	private TrackRepository trackRepository;
	private TranscodeRejectionHandler handler;

	@BeforeEach
	void setUp() {
		trackRepository = mock(TrackRepository.class);
		PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
		when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		handler = new TranscodeRejectionHandler(trackRepository, txManager);
	}

	private Track newTrack(UUID trackId) {
		return Track.builder()
				.trackId(trackId).title("t").durationSeconds(1).originalFilename("a.mp3")
				.fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
				.originalS3Key("original/a.mp3").build();
	}

	@Test
	@DisplayName("트랙이 존재하면 FAILED와 TRANSCODE_QUEUE_FULL로 저장한다")
	void marksTrackFailedWithQueueFullMessage() {
		UUID trackId = UUID.randomUUID();
		Track track = newTrack(trackId);
		when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));

		handler.markQueueFull(trackId);

		assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
		assertThat(track.getErrorMessage()).isEqualTo("TRANSCODE_QUEUE_FULL");
		verify(trackRepository).save(track);
	}

	@Test
	@DisplayName("트랙이 없으면 예외 없이 저장하지 않는다")
	void ignoresMissingTrack() {
		UUID trackId = UUID.randomUUID();
		when(trackRepository.findById(trackId)).thenReturn(Optional.empty());

		assertThatCode(() -> handler.markQueueFull(trackId)).doesNotThrowAnyException();
		verify(trackRepository, never()).save(any());
	}

	@Test
	@DisplayName("갱신 중 예외가 나도 삼킨다")
	void swallowsExceptionDuringUpdate() {
		UUID trackId = UUID.randomUUID();
		when(trackRepository.findById(trackId)).thenThrow(new IllegalStateException("db down"));

		assertThatCode(() -> handler.markQueueFull(trackId)).doesNotThrowAnyException();
	}
}
