package com.streaming.music.playback.repository;

import com.streaming.music.playback.entity.PlaybackLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaybackLogRepository extends JpaRepository<PlaybackLog, Long> {
}
