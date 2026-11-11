package com.streaming.music.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.service.MediaUrlResolver;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class TrackPageResponseTest {

    private final MediaUrlResolver resolver = mock(MediaUrlResolver.class);

    private Track createTrack(String title) {
        return Track.builder()
                .trackId(UUID.randomUUID())
                .title(title).artist("가수").album("앨범")
                .durationSeconds(100)
                .originalFilename("a.mp3").fileExtension("mp3").fileSizeBytes(1L)
                .mimeType("audio/mpeg").originalS3Key("original/x.mp3")
                .status(TrackStatus.READY)
                .createdAt(Instant.parse("2026-11-11T01:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("페이지 메타데이터와 content가 고정 스키마로 변환된다")
    void mapPageMetadata() {
        Track first = createTrack("첫째");
        Track second = createTrack("둘째");
        when(resolver.resolveArtworkUrl(first)).thenReturn("/a1");
        PageImpl<Track> page = new PageImpl<>(List.of(first, second), PageRequest.of(1, 2), 7);

        TrackPageResponse response = TrackPageResponse.from(page, resolver);

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).title()).isEqualTo("첫째");
        assertThat(response.content().get(0).artworkUrl()).isEqualTo("/a1");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7L);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("마지막 페이지는 hasNext가 false이다")
    void lastPageHasNoNext() {
        PageImpl<Track> page = new PageImpl<>(List.of(createTrack("하나")), PageRequest.of(0, 20), 1);

        TrackPageResponse response = TrackPageResponse.from(page, resolver);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 페이지도 변환된다")
    void mapEmptyPage() {
        PageImpl<Track> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

        TrackPageResponse response = TrackPageResponse.from(page, resolver);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    @DisplayName("응답 필드는 PRD 고정 스키마 6개이다")
    void keepFixedSchema() {
        String[] names = Arrays.stream(TrackPageResponse.class.getRecordComponents())
                .map(c -> c.getName()).toArray(String[]::new);

        assertThat(names).containsExactly("content", "page", "size", "totalElements", "totalPages", "hasNext");
    }
}
