package com.streaming.music.track.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streaming.music.user.entity.User;
import com.streaming.music.playback.entity.PlaybackLog;
import com.streaming.music.playback.entity.ActionType;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 엔티티 매핑 검증 (PRD-07 DDL 1:1, T1-1).
 * 임베디드 DB 교체를 막아 local 프로파일(H2 MODE=PostgreSQL) 설정을 그대로 사용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles({"local", "test"})
class EntityMappingTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private User newUser(String providerId, String email) {
		return User.builder()
				.providerId(providerId)
				.email(email)
				.profileImageUrl("https://example.com/p.png")
				.build();
	}

	private Track newTrack(UUID trackId, User uploader) {
		return Track.builder()
				.trackId(trackId)
				.uploader(uploader)
				.title("제목")
				.durationSeconds(180)
				.originalFilename("song.mp3")
				.fileExtension("mp3")
				.fileSizeBytes(1024L)
				.mimeType("audio/mpeg")
				.originalS3Key("original/2026/11/" + trackId + ".mp3")
				.build();
	}

	@Test
	@DisplayName("User, Track, PlaybackLog를 저장하고 조회하며 Track UUID는 앱이 발급한 값을 유지한다")
	void saveAndFindEntities() {
		User user = entityManager.persist(newUser("sub-1", "a@example.com"));
		UUID trackId = UUID.randomUUID();
		Track track = entityManager.persist(newTrack(trackId, user));
		PlaybackLog loginLog = entityManager.persist(PlaybackLog.builder()
				.track(track).user(user).actionType(ActionType.DOWNLOAD).build());
		PlaybackLog anonymousLog = entityManager.persist(PlaybackLog.builder()
				.track(track).build());
		entityManager.flush();
		entityManager.clear();

		assertThat(track.getTrackId()).isEqualTo(trackId);

		Track foundTrack = entityManager.find(Track.class, trackId);
		assertThat(foundTrack.getTitle()).isEqualTo("제목");
		assertThat(foundTrack.getArtist()).isEqualTo(Track.DEFAULT_ARTIST);
		assertThat(foundTrack.getStatus()).isEqualTo(TrackStatus.PENDING);
		assertThat(foundTrack.getAlbum()).isNull();
		assertThat(foundTrack.getArtworkS3Key()).isNull();
		assertThat(foundTrack.getHlsPlaylistKey()).isNull();
		assertThat(foundTrack.getErrorMessage()).isNull();
		assertThat(foundTrack.getUploader().getUserId()).isEqualTo(user.getUserId());

		User foundUser = entityManager.find(User.class, user.getUserId());
		assertThat(foundUser.getProvider()).isEqualTo(User.PROVIDER_GOOGLE);
		assertThat(foundUser.getRole()).isEqualTo(User.ROLE_USER);
		assertThat(foundUser.getProfileImageUrl()).isEqualTo("https://example.com/p.png");

		PlaybackLog foundLoginLog = entityManager.find(PlaybackLog.class, loginLog.getLogId());
		assertThat(foundLoginLog.getUser().getUserId()).isEqualTo(user.getUserId());
		assertThat(foundLoginLog.getActionType()).isEqualTo(ActionType.DOWNLOAD);
		PlaybackLog foundAnonymousLog = entityManager.find(PlaybackLog.class, anonymousLog.getLogId());
		assertThat(foundAnonymousLog.getUser()).isNull();
		assertThat(foundAnonymousLog.getActionType()).isEqualTo(ActionType.STREAM);
	}

	@Test
	@DisplayName("업로더가 없는 Track(uploader=null)도 저장된다")
	void saveTrackWithoutUploader() {
		UUID trackId = UUID.randomUUID();
		entityManager.persist(newTrack(trackId, null));
		entityManager.flush();
		entityManager.clear();

		assertThat(entityManager.find(Track.class, trackId).getUploader()).isNull();
	}

	@Test
	@DisplayName("providerId가 중복되면 유니크 제약 위반 예외가 발생한다")
	void failWhenProviderIdDuplicated() {
		entityManager.persistAndFlush(newUser("sub-dup", "first@example.com"));

		assertThatThrownBy(() -> entityManager.persistAndFlush(newUser("sub-dup", "second@example.com")))
				.isInstanceOf(PersistenceException.class);
	}

	@Test
	@DisplayName("email이 중복되면 유니크 제약 위반 예외가 발생한다")
	void failWhenEmailDuplicated() {
		entityManager.persistAndFlush(newUser("sub-a", "dup@example.com"));

		assertThatThrownBy(() -> entityManager.persistAndFlush(newUser("sub-b", "dup@example.com")))
				.isInstanceOf(PersistenceException.class);
	}

	@Test
	@DisplayName("시간 컬럼이 Instant로 왕복 저장된다")
	void keepInstantOnRoundTrip() {
		Instant createdAt = Instant.parse("2026-01-02T03:04:05.123456Z");
		Instant updatedAt = Instant.parse("2026-01-03T03:04:05.654321Z");
		UUID trackId = UUID.randomUUID();
		entityManager.persist(Track.builder()
				.trackId(trackId).title("t").durationSeconds(1).originalFilename("a.mp3")
				.fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
				.originalS3Key("k").createdAt(createdAt).updatedAt(updatedAt).build());
		entityManager.flush();
		entityManager.clear();

		Track found = entityManager.find(Track.class, trackId);
		assertThat(found.getCreatedAt()).isEqualTo(createdAt);
		assertThat(found.getUpdatedAt()).isEqualTo(updatedAt);
	}

	@Test
	@DisplayName("Track이 갱신되면 updatedAt이 변경되고 createdAt은 유지된다")
	void changeUpdatedAtOnUpdate() {
		Instant past = Instant.parse("2020-01-01T00:00:00Z");
		UUID trackId = UUID.randomUUID();
		entityManager.persist(Track.builder()
				.trackId(trackId).title("t").durationSeconds(1).originalFilename("a.mp3")
				.fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
				.originalS3Key("k").createdAt(past).updatedAt(past).build());
		entityManager.flush();
		entityManager.clear();

		Track found = entityManager.find(Track.class, trackId);
		found.markProcessing();
		entityManager.flush();
		entityManager.clear();

		Track updated = entityManager.find(Track.class, trackId);
		assertThat(updated.getStatus()).isEqualTo(TrackStatus.PROCESSING);
		assertThat(updated.getUpdatedAt()).isAfter(past);
		assertThat(updated.getCreatedAt()).isEqualTo(past);
	}

	@Test
	@DisplayName("PRD-07의 인덱스 3개가 H2 스키마에 생성된다(DESC 컬럼 포함)")
	void createIndexes() {
		var names = jdbcTemplate.queryForList(
				"select lower(index_name) from information_schema.indexes "
						+ "where lower(index_name) like 'idx\\_%' escape '\\'", String.class);

		assertThat(names).contains("idx_tracks_created_at_desc", "idx_tracks_status_created_at",
				"idx_playback_logs_track_id");
	}

	@Test
	@DisplayName("enum이 이름 문자열로 저장된다")
	void storeEnumAsString() {
		UUID trackId = UUID.randomUUID();
		Track track = entityManager.persist(newTrack(trackId, null));
		entityManager.persist(PlaybackLog.builder().track(track).build());
		entityManager.flush();

		String status = jdbcTemplate.queryForObject(
				"select status from tracks where track_id = ?", String.class, trackId);
		String actionType = jdbcTemplate.queryForObject(
				"select action_type from playback_logs where track_id = ?", String.class, trackId);
		assertThat(status).isEqualTo("PENDING");
		assertThat(actionType).isEqualTo("STREAM");
	}
}
