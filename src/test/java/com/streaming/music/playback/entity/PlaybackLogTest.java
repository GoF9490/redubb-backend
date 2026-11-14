package com.streaming.music.playback.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.track.entity.Track;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PlaybackLog 엔티티 단위 테스트 (순수 자바).
 */
class PlaybackLogTest {

	private Track newTrack() {
		return Track.builder().trackId(UUID.randomUUID()).title("t").build();
	}

	@Test
	@DisplayName("이력 유형을 지정하지 않으면 STREAM으로 생성된다")
	void defaultsActionTypeToStreamWhenOmitted() {
		PlaybackLog log = PlaybackLog.builder().track(newTrack()).build();

		assertThat(log.getActionType()).isEqualTo(ActionType.STREAM);
		assertThat(log.getUser()).isNull();
	}

	@Test
	@DisplayName("지정한 이력 유형은 그대로 유지한다")
	void keepsExplicitActionType() {
		PlaybackLog log = PlaybackLog.builder().track(newTrack()).actionType(ActionType.DOWNLOAD).build();

		assertThat(log.getActionType()).isEqualTo(ActionType.DOWNLOAD);
	}

	@Test
	@DisplayName("저장 직전 콜백은 생성 시각이 없으면 현재 시각으로 채운다")
	void fillsCreatedAtOnPrePersistWhenMissing() {
		PlaybackLog log = PlaybackLog.builder().track(newTrack()).build();
		Instant before = Instant.now();

		log.prePersist();

		assertThat(log.getCreatedAt()).isBetween(before, Instant.now());
	}

	@Test
	@DisplayName("저장 직전 콜백은 이미 지정된 생성 시각을 덮어쓰지 않는다")
	void keepsProvidedCreatedAtOnPrePersist() {
		Instant created = Instant.parse("2026-01-01T00:00:00Z");
		PlaybackLog log = PlaybackLog.builder().track(newTrack()).createdAt(created).build();

		log.prePersist();

		assertThat(log.getCreatedAt()).isEqualTo(created);
	}
}
