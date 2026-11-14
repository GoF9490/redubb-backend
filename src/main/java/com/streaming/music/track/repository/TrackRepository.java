package com.streaming.music.track.repository;

import com.streaming.music.track.entity.Track;
import com.streaming.music.track.entity.TrackStatus;
import com.streaming.music.global.util.LikePatternEscaper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 모든 목록 조회는 {@code createdAt DESC} 고정 정렬이다.
 * 호출자는 정렬 없는 {@code PageRequest.of(page, size)}만 전달한다.
 * keyword/status 유무에 따른 메서드 선택은 Service 책임이다.
 */
public interface TrackRepository extends JpaRepository<Track, UUID> {

	/** 전체 최신순 조회. */
	Page<Track> findAllByOrderByCreatedAtDesc(Pageable pageable);

	/** 상태 필터 + 최신순 조회. */
	Page<Track> findByStatusOrderByCreatedAtDesc(TrackStatus status, Pageable pageable);

	/**
	 * 제목 또는 가수 부분 일치(대소문자 무시) 검색.
	 * {@code pattern}은 {@link LikePatternEscaper#toContainsPattern(String)}으로 만든 값이다.
	 */
	@Query("select t from Track t "
			+ "where lower(t.title) like lower(:pattern) escape '\\' "
			+ "or lower(t.artist) like lower(:pattern) escape '\\' "
			+ "order by t.createdAt desc")
	Page<Track> searchByKeyword(@Param("pattern") String pattern, Pageable pageable);

	/** 키워드 검색 + 상태 필터. */
	@Query("select t from Track t "
			+ "where (lower(t.title) like lower(:pattern) escape '\\' "
			+ "or lower(t.artist) like lower(:pattern) escape '\\') "
			+ "and t.status = :status "
			+ "order by t.createdAt desc")
	Page<Track> searchByKeywordAndStatus(@Param("pattern") String pattern,
			@Param("status") TrackStatus status,
			Pageable pageable);

	/**
	 * updatedAt이 threshold 이전인 PENDING/PROCESSING 트랙을 FAILED로 일괄 갱신한다.
	 * 벌크 쿼리는 @PreUpdate가 동작하지 않으므로 updatedAt을 직접 지정한다.
	 * errorMessage 길이(500자) 절단은 호출자 책임이다.
	 *
	 * @return 갱신된 건수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Track t set t.status = com.streaming.music.track.entity.TrackStatus.FAILED, "
			+ "t.errorMessage = :errorMessage, t.updatedAt = :now "
			+ "where t.status in (com.streaming.music.track.entity.TrackStatus.PENDING, "
			+ "com.streaming.music.track.entity.TrackStatus.PROCESSING) "
			+ "and t.updatedAt < :threshold")
	int failStaleTracks(@Param("threshold") Instant threshold,
						@Param("now") Instant now,
						@Param("errorMessage") String errorMessage);
}
