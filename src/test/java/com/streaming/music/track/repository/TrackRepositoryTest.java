package com.streaming.music.track.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.global.util.LikePatternEscaper;
import com.streaming.music.track.entity.Track;
import com.streaming.music.track.entity.TrackStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles({"local", "test"})
class TrackRepositoryTest {

	private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private TrackRepository trackRepository;

	private Track saveTrack(String title, String artist, TrackStatus status,
							Instant createdAt, Instant updatedAt) {
		UUID trackId = UUID.randomUUID();
		Track track = Track.builder()
				.trackId(trackId).title(title).artist(artist).status(status)
				.durationSeconds(100).originalFilename("a.mp3").fileExtension("mp3")
				.fileSizeBytes(1L).mimeType("audio/mpeg")
				.originalS3Key("original/" + trackId + ".mp3")
				.createdAt(createdAt).updatedAt(updatedAt).build();
		return entityManager.persist(track);
	}

	private Track saveTrack(String title, String artist, TrackStatus status, int dayOffset) {
		Instant time = BASE.plusSeconds(dayOffset * 86400L);
		return saveTrack(title, artist, status, time, time);
	}

	private List<String> titlesOf(Page<Track> page) {
		return page.getContent().stream().map(Track::getTitle).toList();
	}

	@Test
	@DisplayName("keyword가 제목 또는 가수에 부분 일치하면 대소문자 무시하고 조회된다")
	void searchByKeywordMatchesTitleOrArtist() {
		saveTrack("Hello World", "Adele", TrackStatus.READY, 1);
		saveTrack("Rolling", "HELLO Band", TrackStatus.READY, 2);
		saveTrack("Other", "Nobody", TrackStatus.READY, 3);
		entityManager.flush();
		entityManager.clear();

		Page<Track> result = trackRepository.searchByKeyword(
				LikePatternEscaper.toContainsPattern("hello"), PageRequest.of(0, 10));

		assertThat(titlesOf(result)).containsExactly("Rolling", "Hello World");
		assertThat(result.getTotalElements()).isEqualTo(2);
	}

	@Test
	@DisplayName("keyword의 %와 _는 와일드카드가 아닌 리터럴로 검색된다")
	void searchByKeywordEscapesWildcards() {
		saveTrack("100% Love", "A", TrackStatus.READY, 1);
		saveTrack("1000 Love", "A", TrackStatus.READY, 2);
		saveTrack("a_b", "A", TrackStatus.READY, 3);
		saveTrack("axb", "A", TrackStatus.READY, 4);
		entityManager.flush();
		entityManager.clear();

		assertThat(titlesOf(trackRepository.searchByKeyword(
				LikePatternEscaper.toContainsPattern("100%"), PageRequest.of(0, 10))))
				.containsExactly("100% Love");
		assertThat(titlesOf(trackRepository.searchByKeyword(
				LikePatternEscaper.toContainsPattern("a_b"), PageRequest.of(0, 10))))
				.containsExactly("a_b");
	}

	@Test
	@DisplayName("keyword 없이 전체 최신순으로 페이징 조회된다")
	void findAllOrderByCreatedAtDesc() {
		saveTrack("first", "A", TrackStatus.READY, 1);
		saveTrack("second", "A", TrackStatus.PENDING, 3);
		saveTrack("third", "A", TrackStatus.FAILED, 2);
		entityManager.flush();
		entityManager.clear();

		Page<Track> page = trackRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2));

		assertThat(titlesOf(page)).containsExactly("second", "third");
		assertThat(page.getTotalElements()).isEqualTo(3);
		assertThat(page.getTotalPages()).isEqualTo(2);
		assertThat(page.hasNext()).isTrue();
	}

	@Test
	@DisplayName("status 필터는 단독 및 keyword와 조합되어 적용된다")
	void filterByStatus() {
		saveTrack("song a", "X", TrackStatus.READY, 1);
		saveTrack("song b", "X", TrackStatus.FAILED, 2);
		saveTrack("song c", "X", TrackStatus.READY, 3);
		saveTrack("other", "X", TrackStatus.READY, 4);
		entityManager.flush();
		entityManager.clear();

		assertThat(titlesOf(trackRepository.findByStatusOrderByCreatedAtDesc(
				TrackStatus.READY, PageRequest.of(0, 10))))
				.containsExactly("other", "song c", "song a");
		assertThat(titlesOf(trackRepository.searchByKeywordAndStatus(
				LikePatternEscaper.toContainsPattern("SONG"), TrackStatus.READY,
				PageRequest.of(0, 10))))
				.containsExactly("song c", "song a");
	}

	@Test
	@DisplayName("기준 시각 이전의 PENDING/PROCESSING만 FAILED로 바뀌고 READY와 경계값은 불변이다")
	void failStaleTracks() {
		Instant threshold = BASE.plusSeconds(1000);
		Instant now = BASE.plusSeconds(5000);
		Track oldPending = saveTrack("oldPending", "A", TrackStatus.PENDING, BASE, BASE.plusSeconds(10));
		Track oldProcessing = saveTrack("oldProcessing", "A", TrackStatus.PROCESSING, BASE, BASE.plusSeconds(20));
		Track oldReady = saveTrack("oldReady", "A", TrackStatus.READY, BASE, BASE.plusSeconds(30));
		Track boundary = saveTrack("boundary", "A", TrackStatus.PROCESSING, BASE, threshold);
		Track recent = saveTrack("recent", "A", TrackStatus.PENDING, BASE, threshold.plusSeconds(1));

		int updated = trackRepository.failStaleTracks(threshold, now, "INTERRUPTED_BY_RESTART");

		assertThat(updated).isEqualTo(2);
		Track failedPending = trackRepository.findById(oldPending.getTrackId()).orElseThrow();
		assertThat(failedPending.getStatus()).isEqualTo(TrackStatus.FAILED);
		assertThat(failedPending.getErrorMessage()).isEqualTo("INTERRUPTED_BY_RESTART");
		assertThat(failedPending.getUpdatedAt()).isEqualTo(now);
		assertThat(trackRepository.findById(oldProcessing.getTrackId()).orElseThrow().getStatus())
				.isEqualTo(TrackStatus.FAILED);
		Track readyAfter = trackRepository.findById(oldReady.getTrackId()).orElseThrow();
		assertThat(readyAfter.getStatus()).isEqualTo(TrackStatus.READY);
		assertThat(readyAfter.getErrorMessage()).isNull();
		assertThat(trackRepository.findById(boundary.getTrackId()).orElseThrow().getStatus())
				.isEqualTo(TrackStatus.PROCESSING);
		assertThat(trackRepository.findById(recent.getTrackId()).orElseThrow().getStatus())
				.isEqualTo(TrackStatus.PENDING);
	}

	@Test
	@DisplayName("대상이 없으면 0건을 반환한다")
	void failStaleTracksReturnsZero() {
		saveTrack("ready", "A", TrackStatus.READY, 1);

		assertThat(trackRepository.failStaleTracks(BASE.plusSeconds(999999), BASE, "x")).isZero();
	}
}
