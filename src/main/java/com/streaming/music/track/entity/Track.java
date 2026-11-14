package com.streaming.music.track.entity;

import com.streaming.music.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 음원 메타데이터 및 변환 상태 엔티티 (PRD-07 tracks).
 *
 * <p>PK(trackId)는 {@code @GeneratedValue} 없이 애플리케이션이 UUID를 발급한다.
 * 따라서 {@code save()}는 merge로 동작하므로 호출자는 반환된 인스턴스를 사용해야 한다.
 */
@Entity
@Table(name = "tracks", indexes = {
		@Index(name = "idx_tracks_created_at_desc", columnList = "created_at DESC"),
		@Index(name = "idx_tracks_status_created_at", columnList = "status, created_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Track {

	public static final String DEFAULT_ARTIST = "Unknown Artist";
	private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

	@Id
	@Column(name = "track_id", nullable = false, updatable = false)
	private UUID trackId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "uploader_id")
	private User uploader;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "artist", nullable = false, length = 150)
	private String artist = DEFAULT_ARTIST;

	@Column(name = "album", length = 200)
	private String album;

	@Column(name = "duration_seconds", nullable = false)
	private int durationSeconds;

	@Column(name = "original_filename", nullable = false, length = 255)
	private String originalFilename;

	@Column(name = "file_extension", nullable = false, length = 20)
	private String fileExtension;

	@Column(name = "file_size_bytes", nullable = false)
	private long fileSizeBytes;

	@Column(name = "mime_type", nullable = false, length = 100)
	private String mimeType;

	@Column(name = "original_s3_key", nullable = false, length = 500)
	private String originalS3Key;

	@Column(name = "artwork_s3_key", length = 500)
	private String artworkS3Key;

	@Column(name = "hls_playlist_key", length = 500)
	private String hlsPlaylistKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private TrackStatus status = TrackStatus.PENDING;

	@Column(name = "error_message", length = 500)
	private String errorMessage;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Builder
	private Track(UUID trackId, User uploader, String title, String artist, String album,
			int durationSeconds, String originalFilename, String fileExtension,
			long fileSizeBytes, String mimeType, String originalS3Key, String artworkS3Key,
			String hlsPlaylistKey, TrackStatus status, String errorMessage,
			Instant createdAt, Instant updatedAt) {
		this.trackId = trackId;
		this.uploader = uploader;
		this.title = title;
		if (artist != null) {
			this.artist = artist;
		}
		this.album = album;
		this.durationSeconds = durationSeconds;
		this.originalFilename = originalFilename;
		this.fileExtension = fileExtension;
		this.fileSizeBytes = fileSizeBytes;
		this.mimeType = mimeType;
		this.originalS3Key = originalS3Key;
		this.artworkS3Key = artworkS3Key;
		this.hlsPlaylistKey = hlsPlaylistKey;
		if (status != null) {
			this.status = status;
		}
		this.errorMessage = errorMessage;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	@PrePersist
	void prePersist() {
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		if (updatedAt == null) {
			updatedAt = now;
		}
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = Instant.now();
	}

	/** 변환 시작 상태로 전이한다. */
	public void markProcessing() {
		this.status = TrackStatus.PROCESSING;
	}

	/** 변환 완료 상태로 전이하고 HLS 플레이리스트 키를 기록한다. */
	public void markReady(String hlsPlaylistKey) {
		this.status = TrackStatus.READY;
		this.hlsPlaylistKey = hlsPlaylistKey;
		this.errorMessage = null;
	}

	/** 변환 실패 상태로 전이한다. 메시지는 DDL 길이(500자)에 맞춰 절단한다. */
	public void markFailed(String message) {
		this.status = TrackStatus.FAILED;
		this.errorMessage = (message != null && message.length() > ERROR_MESSAGE_MAX_LENGTH)
				? message.substring(0, ERROR_MESSAGE_MAX_LENGTH)
				: message;
	}
}
