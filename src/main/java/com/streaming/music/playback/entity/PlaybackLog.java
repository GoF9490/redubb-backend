package com.streaming.music.playback.entity;

import com.streaming.music.track.entity.Track;
import com.streaming.music.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스트리밍/다운로드 이력 엔티티 (PRD-07 playback_logs). 비로그인 이벤트는 user가 null이다.
 */
@Entity
@Table(name = "playback_logs", indexes = {
		@Index(name = "idx_playback_logs_track_id", columnList = "track_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaybackLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "log_id")
	private Long logId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "track_id", nullable = false)
	private Track track;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "action_type", nullable = false, length = 20)
	private ActionType actionType = ActionType.STREAM;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Builder
	private PlaybackLog(Track track, User user, ActionType actionType, Instant createdAt) {
		this.track = track;
		this.user = user;
		if (actionType != null) {
			this.actionType = actionType;
		}
		this.createdAt = createdAt;
	}

	@PrePersist
	void prePersist() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}
}
