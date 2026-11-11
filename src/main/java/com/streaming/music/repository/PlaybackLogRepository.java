package com.streaming.music.repository;

import com.streaming.music.entity.PlaybackLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaybackLogRepository extends JpaRepository<PlaybackLog, Long> {
}
