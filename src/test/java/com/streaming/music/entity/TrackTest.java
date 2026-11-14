package com.streaming.music.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Track 상태 전이 단위 테스트 (순수 자바).
 */
class TrackTest {

	private Track newTrack() {
		return Track.builder()
				.trackId(UUID.randomUUID()).title("t").durationSeconds(1)
				.originalFilename("a.mp3").fileExtension("mp3").fileSizeBytes(1L)
				.mimeType("audio/mpeg").originalS3Key("k").build();
	}

	@Test
	@DisplayName("PENDING에서 PROCESSING, READY로 전이하며 HLS 키를 기록한다")
	void transitToReady() {
		Track track = newTrack();
		assertThat(track.getStatus()).isEqualTo(TrackStatus.PENDING);

		track.markProcessing();
		assertThat(track.getStatus()).isEqualTo(TrackStatus.PROCESSING);

		track.markReady("hls/x/index.m3u8");
		assertThat(track.getStatus()).isEqualTo(TrackStatus.READY);
		assertThat(track.getHlsPlaylistKey()).isEqualTo("hls/x/index.m3u8");
	}

	@Test
	@DisplayName("FAILED 전이 시 에러 메시지를 500자로 절단한다")
	void truncateErrorMessage() {
		Track track = newTrack();

		track.markFailed("x".repeat(600));

		assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
		assertThat(track.getErrorMessage()).hasSize(500);
	}

	@Test
	@DisplayName("FAILED 전이 시 메시지가 null이어도 안전하게 처리한다")
	void markFailedWithNullMessage() {
		Track track = newTrack();

		track.markFailed(null);

		assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
		assertThat(track.getErrorMessage()).isNull();
	}
}
