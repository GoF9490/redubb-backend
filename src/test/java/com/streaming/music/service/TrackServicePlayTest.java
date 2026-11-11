package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.streaming.music.config.properties.AudioProperties;
import com.streaming.music.config.properties.StorageProperties;
import com.streaming.music.dto.response.StreamPlayResponse;
import com.streaming.music.entity.ActionType;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.exception.TrackNotFoundException;
import com.streaming.music.exception.TrackNotReadyException;
import com.streaming.music.repository.TrackRepository;
import com.streaming.music.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** TrackService 재생 진입점(getPlayInfo) 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class TrackServicePlayTest {

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
    private MediaUrlResolver mediaUrlResolver;
    @Mock
    private PlaybackLogService playbackLogService;

    private TrackService trackService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-05T10:00:00Z"), ZoneOffset.UTC);
        trackService = new TrackService(storageService, audioMetadataValidator, transcodeTaskDispatcher,
                trackRepository, userRepository, audioProperties, clock, mock(PlatformTransactionManager.class),
                mediaUrlResolver, playbackLogService,
                new StorageProperties("http://rustfs:9000", "us-east-1", "ak", "sk", "audio-storage", 60));
    }

    private Track buildTrack(TrackStatus status, String hlsPlaylistKey, String artworkKey) {
        return Track.builder()
                .trackId(UUID.randomUUID()).title("제목").artist("가수").durationSeconds(215)
                .originalFilename("a.mp3").fileExtension("mp3").fileSizeBytes(3L).mimeType("audio/mpeg")
                .originalS3Key("original/k.mp3").artworkS3Key(artworkKey)
                .hlsPlaylistKey(hlsPlaylistKey).status(status)
                .build();
    }

    @ParameterizedTest
    @EnumSource(value = TrackStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    @DisplayName("READY가 아닌 트랙은 TrackNotReadyException이고 이력을 기록하지 않는다")
    void rejectNotReadyTrack(TrackStatus status) {
        Track track = buildTrack(status, null, null);
        when(trackRepository.findById(track.getTrackId())).thenReturn(Optional.of(track));

        assertThatThrownBy(() -> trackService.getPlayInfo(track.getTrackId(), 1L))
                .isInstanceOf(TrackNotReadyException.class);

        verifyNoInteractions(playbackLogService);
    }

    @Test
    @DisplayName("READY 트랙은 6개 필드 응답을 반환하고 STREAM 이력을 1회 기록한다")
    void returnPlayInfoForReadyTrack() {
        Track track = buildTrack(TrackStatus.READY, "stream/x/index.m3u8", "artwork/x.jpg");
        when(trackRepository.findById(track.getTrackId())).thenReturn(Optional.of(track));
        when(mediaUrlResolver.resolveStreamUrl(track)).thenReturn("/stream/x/index.m3u8");
        when(mediaUrlResolver.resolveArtworkUrl(track)).thenReturn("/artwork/x.jpg");

        StreamPlayResponse response = trackService.getPlayInfo(track.getTrackId(), 7L);

        assertThat(response).isEqualTo(new StreamPlayResponse(track.getTrackId(), "/stream/x/index.m3u8",
                "제목", "가수", 215, "/artwork/x.jpg"));
        verify(playbackLogService, times(1)).recordLog(track, 7L, ActionType.STREAM);
    }

    @Test
    @DisplayName("비로그인(userId null)이어도 STREAM 이력을 userId null로 기록한다")
    void recordLogWithNullUserId() {
        Track track = buildTrack(TrackStatus.READY, "stream/x/index.m3u8", null);
        when(trackRepository.findById(track.getTrackId())).thenReturn(Optional.of(track));
        when(mediaUrlResolver.resolveStreamUrl(track)).thenReturn("/stream/x/index.m3u8");
        when(mediaUrlResolver.resolveArtworkUrl(track)).thenReturn(null);

        StreamPlayResponse response = trackService.getPlayInfo(track.getTrackId(), null);

        assertThat(response.artworkUrl()).isNull();
        verify(playbackLogService, times(1)).recordLog(track, null, ActionType.STREAM);
    }

    @Test
    @DisplayName("없는 트랙은 TrackNotFoundException이고 이력을 기록하지 않는다")
    void rejectMissingTrack() {
        UUID trackId = UUID.randomUUID();
        when(trackRepository.findById(trackId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackService.getPlayInfo(trackId, 1L))
                .isInstanceOf(TrackNotFoundException.class);

        verifyNoInteractions(playbackLogService);
    }

    @Test
    @DisplayName("READY인데 HLS 플레이리스트 키가 없으면 409이고 이력을 기록하지 않는다")
    void rejectReadyTrackWithoutPlaylistKey() {
        Track track = buildTrack(TrackStatus.READY, null, null);
        when(trackRepository.findById(track.getTrackId())).thenReturn(Optional.of(track));

        assertThatThrownBy(() -> trackService.getPlayInfo(track.getTrackId(), 1L))
                .isInstanceOf(TrackNotReadyException.class);

        verifyNoInteractions(playbackLogService);
    }
}
