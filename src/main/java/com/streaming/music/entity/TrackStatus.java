package com.streaming.music.entity;

/**
 * 음원 비동기 변환 상태 (PRD-07 chk_tracks_status).
 */
public enum TrackStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED
}
