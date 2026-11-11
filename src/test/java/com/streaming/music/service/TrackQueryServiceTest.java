package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.streaming.music.config.properties.AudioProperties;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.exception.InvalidRequestException;
import com.streaming.music.exception.TrackNotFoundException;
import com.streaming.music.repository.LikePatternEscaper;
import com.streaming.music.repository.TrackRepository;
import com.streaming.music.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/** TrackService 조회(getTracks/getTrack) 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class TrackQueryServiceTest {

    @Mock
    private StorageService storageService;
    @Mock
    private AudioMetadataValidator audioMetadataValidator;
    @Mock
    private TranscodeTaskDispatcher transcodeTaskDispatcher;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AudioProperties audioProperties;
    @Mock
    private PlatformTransactionManager transactionManager;

    private TrackService trackService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-05T10:00:00Z"), ZoneOffset.UTC);
        trackService = new TrackService(storageService, audioMetadataValidator, transcodeTaskDispatcher,
                trackRepository, userRepository, audioProperties, clock, transactionManager);
    }

    private Page<Track> emptyPage() {
        return new PageImpl<>(List.of());
    }

    private Pageable captureFindAllPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(trackRepository).findAllByOrderByCreatedAtDesc(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("keyword와 status가 없으면 전체 최신순 조회를 호출한다")
    void getTracksWithoutKeywordAndStatus() {
        when(trackRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(emptyPage());

        trackService.getTracks(null, null, 2, 20);

        Pageable pageable = captureFindAllPageable();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().isSorted()).isFalse();
        verifyNoMoreInteractions(trackRepository);
    }

    @Test
    @DisplayName("keyword 없이 status만 있으면 상태별 최신순 조회를 호출한다")
    void getTracksWithStatusOnly() {
        when(trackRepository.findByStatusOrderByCreatedAtDesc(any(TrackStatus.class), any(Pageable.class)))
                .thenReturn(emptyPage());

        trackService.getTracks("  ", TrackStatus.READY, 0, 20);

        verify(trackRepository).findByStatusOrderByCreatedAtDesc(any(TrackStatus.class), any(Pageable.class));
        verifyNoMoreInteractions(trackRepository);
    }

    @Test
    @DisplayName("keyword만 있으면 trim한 패턴으로 검색 쿼리를 호출한다")
    void getTracksWithKeywordOnly() {
        when(trackRepository.searchByKeyword(any(String.class), any(Pageable.class))).thenReturn(emptyPage());

        trackService.getTracks("  노래 ", null, 0, 20);

        verify(trackRepository).searchByKeyword(
                org.mockito.ArgumentMatchers.eq(LikePatternEscaper.toContainsPattern("노래")),
                any(Pageable.class));
        verifyNoMoreInteractions(trackRepository);
    }

    @Test
    @DisplayName("keyword와 status가 모두 있으면 상태 조합 검색 쿼리를 호출한다")
    void getTracksWithKeywordAndStatus() {
        when(trackRepository.searchByKeywordAndStatus(any(String.class), any(TrackStatus.class),
                any(Pageable.class))).thenReturn(emptyPage());

        trackService.getTracks("노래", TrackStatus.PENDING, 0, 20);

        verify(trackRepository).searchByKeywordAndStatus(
                org.mockito.ArgumentMatchers.eq(LikePatternEscaper.toContainsPattern("노래")),
                org.mockito.ArgumentMatchers.eq(TrackStatus.PENDING), any(Pageable.class));
        verifyNoMoreInteractions(trackRepository);
    }

    @Test
    @DisplayName("size가 100을 넘으면 100으로 보정한다")
    void getTracksClampsSizeToMax() {
        when(trackRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(emptyPage());

        trackService.getTracks(null, null, 0, 500);

        assertThat(captureFindAllPageable().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("size가 정확히 100이면 그대로 사용한다")
    void getTracksKeepsMaxSize() {
        when(trackRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(emptyPage());

        trackService.getTracks(null, null, 0, 100);

        assertThat(captureFindAllPageable().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("page가 음수이면 INVALID_REQUEST 예외이고 조회하지 않는다")
    void getTracksRejectsNegativePage() {
        assertThatThrownBy(() -> trackService.getTracks(null, null, -1, 20))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(trackRepository);
    }

    @Test
    @DisplayName("size가 1 미만이면 INVALID_REQUEST 예외이고 조회하지 않는다")
    void getTracksRejectsNonPositiveSize() {
        assertThatThrownBy(() -> trackService.getTracks(null, null, 0, 0))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(trackRepository);
    }

    @Test
    @DisplayName("trim 후 keyword가 200자를 넘으면 예외, 200자는 허용한다")
    void getTracksChecksKeywordLengthAfterTrim() {
        when(trackRepository.searchByKeyword(any(String.class), any(Pageable.class))).thenReturn(emptyPage());

        trackService.getTracks(" " + "a".repeat(200) + " ", null, 0, 20);

        assertThatThrownBy(() -> trackService.getTracks("a".repeat(201), null, 0, 20))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("존재하지 않는 id는 TrackNotFoundException이다")
    void getTrackThrowsWhenMissing() {
        UUID trackId = UUID.randomUUID();
        when(trackRepository.findById(trackId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackService.getTrack(trackId)).isInstanceOf(TrackNotFoundException.class);
    }

    @Test
    @DisplayName("PENDING·FAILED 트랙도 상세 조회가 가능하다")
    void getTrackAllowsAnyStatus() {
        for (TrackStatus status : TrackStatus.values()) {
            Track track = mock(Track.class);
            UUID trackId = UUID.randomUUID();
            when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));

            assertThat(trackService.getTrack(trackId)).isSameAs(track);
        }
    }
}
