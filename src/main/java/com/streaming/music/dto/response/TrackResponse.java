package com.streaming.music.dto.response;

import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.service.MediaUrlResolver;
import java.time.Instant;
import java.util.UUID;

/**
 * 트랙 응답 DTO (PRD-05 §7.1.1).
 *
 * <p>errorMessage는 내부 진단용이므로 포함하지 않는다(status == FAILED만 노출).
 * artworkUrl은 {@link MediaUrlResolver}를 경유해 조립한다.
 */
public record TrackResponse(
        UUID trackId,
        String title,
        String artist,
        String album,
        int durationSeconds,
        TrackStatus status,
        String artworkUrl,
        String originalFilename,
        String fileExtension,
        long fileSizeBytes,
        Instant createdAt
) {

    /** 엔티티를 응답 DTO로 변환한다. 목록·상세 응답에서도 재사용한다. */
    public static TrackResponse from(Track track, MediaUrlResolver mediaUrlResolver) {
        return new TrackResponse(
                track.getTrackId(),
                track.getTitle(),
                track.getArtist(),
                track.getAlbum(),
                track.getDurationSeconds(),
                track.getStatus(),
                mediaUrlResolver.resolveArtworkUrl(track),
                track.getOriginalFilename(),
                track.getFileExtension(),
                track.getFileSizeBytes(),
                track.getCreatedAt()
        );
    }
}
