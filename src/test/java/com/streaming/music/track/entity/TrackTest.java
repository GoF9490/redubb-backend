package com.streaming.music.track.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
	@DisplayName("FAILED 전이 시 컬럼 길이(500자)와 같은 메시지는 절단하지 않는다")
	void keepsErrorMessageOfExactColumnLength() {
		Track track = newTrack();

		track.markFailed("x".repeat(Track.ERROR_MESSAGE_MAX_LENGTH));

		assertThat(track.getErrorMessage()).hasSize(Track.ERROR_MESSAGE_MAX_LENGTH);
	}
	@Test
	@DisplayName("PROCESSING에서 FAILED로 전이하며 에러 메시지를 기록한다")
	void transitsToFailedFromProcessing() {
		Track track = newTrack();
		track.markProcessing();

		track.markFailed("FFMPEG_TIMEOUT");

		assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
		assertThat(track.getErrorMessage()).isEqualTo("FFMPEG_TIMEOUT");
	}

	@Test
	@DisplayName("기존 에러 메시지가 있어도 READY로 전이하면 에러 메시지가 비워진다")
	void clearsErrorMessageWhenMarkedReady() {
		Track track = Track.builder()
				.trackId(UUID.randomUUID()).title("t").durationSeconds(1)
				.originalFilename("a.mp3").fileExtension("mp3").fileSizeBytes(1L)
				.mimeType("audio/mpeg").originalS3Key("k")
				.status(TrackStatus.PROCESSING).errorMessage("이전 오류").build();

		track.markReady("hls/x/index.m3u8");

		assertThat(track.getErrorMessage()).isNull();
	}

	@Test
	@DisplayName("가수와 상태를 지정하지 않으면 기본 가수명과 PENDING으로 생성된다")
	void defaultsArtistAndStatusWhenOmitted() {
		Track track = newTrack();

		assertThat(track.getArtist()).isEqualTo(Track.DEFAULT_ARTIST);
		assertThat(track.getStatus()).isEqualTo(TrackStatus.PENDING);
	}

	@Test
	@DisplayName("저장 직전 콜백은 생성·수정 시각이 없으면 현재 시각으로 채운다")
	void fillsTimestampsOnPrePersistWhenMissing() {
		Track track = newTrack();
		Instant before = Instant.now();

		track.prePersist();

		assertThat(track.getCreatedAt()).isBetween(before, Instant.now());
		assertThat(track.getUpdatedAt()).isBetween(before, Instant.now());
	}

	@Test
	@DisplayName("저장 직전 콜백은 이미 지정된 생성·수정 시각을 덮어쓰지 않는다")
	void keepsProvidedTimestampsOnPrePersist() {
		Instant created = Instant.parse("2026-01-01T00:00:00Z");
		Instant updated = Instant.parse("2026-01-02T00:00:00Z");
		Track track = Track.builder()
				.trackId(UUID.randomUUID()).title("t").durationSeconds(1)
				.originalFilename("a.mp3").fileExtension("mp3").fileSizeBytes(1L)
				.mimeType("audio/mpeg").originalS3Key("k")
				.createdAt(created).updatedAt(updated).build();

		track.prePersist();

		assertThat(track.getCreatedAt()).isEqualTo(created);
		assertThat(track.getUpdatedAt()).isEqualTo(updated);
	}

	@Test
	@DisplayName("수정 직전 콜백은 수정 시각을 현재 시각으로 갱신한다")
	void refreshesUpdatedAtOnPreUpdate() {
		Instant old = Instant.parse("2026-01-02T00:00:00Z");
		Track track = Track.builder()
				.trackId(UUID.randomUUID()).title("t").durationSeconds(1)
				.originalFilename("a.mp3").fileExtension("mp3").fileSizeBytes(1L)
				.mimeType("audio/mpeg").originalS3Key("k").updatedAt(old).build();
		Instant before = Instant.now();

		track.preUpdate();

		assertThat(track.getUpdatedAt()).isBetween(before, Instant.now());
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
