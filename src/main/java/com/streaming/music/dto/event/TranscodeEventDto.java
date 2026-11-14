package com.streaming.music.dto.event;

import java.util.Objects;
import java.util.UUID;

/**
 * 비동기 HLS 변환을 요청하는 이벤트 페이로드.
 * <p>
 * 파일 객체나 로컬 경로는 담지 않고, 스토리지 식별자(trackId, originalS3Key)만 전달한다.
 *
 * @param trackId       변환 대상 트랙 ID
 * @param originalS3Key 스토리지에 저장된 원본 음원의 S3 키
 */
public record TranscodeEventDto(UUID trackId, String originalS3Key) {

	public TranscodeEventDto {
		// 비동기 스레드에서 늦게 NPE가 나지 않도록 생성 시점에 차단
		Objects.requireNonNull(trackId, "trackId must not be null");
		Objects.requireNonNull(originalS3Key, "originalS3Key must not be null");
	}
}
