package com.streaming.music.exception;

/**
 * 존재하지 않는 trackId를 조회한 경우 (404).
 */
public class TrackNotFoundException extends BusinessException {

    public TrackNotFoundException() {
        super(ErrorCode.TRACK_NOT_FOUND);
    }

    public TrackNotFoundException(String message) {
        super(ErrorCode.TRACK_NOT_FOUND, message);
    }

    public TrackNotFoundException(String message, Throwable cause) {
        super(ErrorCode.TRACK_NOT_FOUND, message, cause);
    }
}
