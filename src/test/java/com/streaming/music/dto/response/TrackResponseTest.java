package com.streaming.music.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.service.MediaUrlResolver;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrackResponseTest {

    private final MediaUrlResolver resolver = mock(MediaUrlResolver.class);

    private Track createTrack(TrackStatus status, String errorMessage) {
        return Track.builder()
                .trackId(UUID.randomUUID())
                .title("제목").artist("가수").album("앨범")
                .durationSeconds(123)
                .originalFilename("a.mp3").fileExtension("mp3").fileSizeBytes(999L)
                .mimeType("audio/mpeg").originalS3Key("original/2026/11/x.mp3")
                .artworkS3Key("artwork/x.jpg")
                .status(status).errorMessage(errorMessage)
                .createdAt(Instant.parse("2026-11-11T01:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("엔티티의 모든 필드가 응답으로 매핑되고 artworkUrl은 resolver를 경유한다")
    void mapAllFields() {
        Track track = createTrack(TrackStatus.PENDING, null);
        when(resolver.resolveArtworkUrl(track)).thenReturn("/artwork/x.jpg");

        TrackResponse response = TrackResponse.from(track, resolver);

        assertThat(response.trackId()).isEqualTo(track.getTrackId());
        assertThat(response.title()).isEqualTo("제목");
        assertThat(response.artist()).isEqualTo("가수");
        assertThat(response.album()).isEqualTo("앨범");
        assertThat(response.durationSeconds()).isEqualTo(123);
        assertThat(response.status()).isEqualTo(TrackStatus.PENDING);
        assertThat(response.artworkUrl()).isEqualTo("/artwork/x.jpg");
        assertThat(response.originalFilename()).isEqualTo("a.mp3");
        assertThat(response.fileExtension()).isEqualTo("mp3");
        assertThat(response.fileSizeBytes()).isEqualTo(999L);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-11-11T01:00:00Z"));
    }

    @Test
    @DisplayName("응답 필드는 PRD 11개이며 errorMessage와 streamUrl을 포함하지 않는다")
    void excludeInternalFields() {
        String[] names = Arrays.stream(TrackResponse.class.getRecordComponents())
                .map(c -> c.getName()).toArray(String[]::new);

        assertThat(names).containsExactly("trackId", "title", "artist", "album", "durationSeconds",
                "status", "artworkUrl", "originalFilename", "fileExtension", "fileSizeBytes", "createdAt");
    }

    @Test
    @DisplayName("FAILED 트랙은 status만 FAILED로 노출한다")
    void exposeFailedStatusOnly() {
        Track track = createTrack(TrackStatus.FAILED, "내부 오류");

        TrackResponse response = TrackResponse.from(track, resolver);

        assertThat(response.status()).isEqualTo(TrackStatus.FAILED);
        assertThat(response.artworkUrl()).isNull();
    }
}
