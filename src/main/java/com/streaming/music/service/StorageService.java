package com.streaming.music.service;

import java.nio.file.Path;

/**
 * 오브젝트 스토리지(RustFS, S3 호환) 추상화. 서비스 단위 테스트에서는 Mock으로 대체한다.
 * 스토리지 연산 실패는 {@link com.streaming.music.exception.StorageException}으로 변환된다.
 */
public interface StorageService {

	/** 버킷이 없으면 생성한다. 이미 있으면 생성을 호출하지 않는다. */
	void ensureBucketExists();

	/** 로컬 파일을 지정한 키와 Content-Type으로 업로드한다. */
	void uploadFile(String key, Path file, String contentType);

	/** 바이트 배열(앨범아트 등)을 지정한 키와 Content-Type으로 업로드한다. */
	void uploadBytes(String key, byte[] data, String contentType);

	/** 객체를 삭제한다(보상 처리용). 없는 객체 삭제는 S3 규약상 오류가 아니다. */
	void deleteObject(String key);

	/** presigned GET URL을 생성한다. 서명 계산만 수행하며 네트워크 호출은 없다. */
	String createPresignedGetUrl(String key, int expirySeconds);

	/**
	 * 로컬 디렉터리의 모든 일반 파일을 {@code keyPrefix/<상대경로>} 키로 재귀 업로드한다.
	 * 확장자별 Content-Type을 지정하며(.m3u8, .ts, 그 외 octet-stream), 첫 실패 시 즉시 예외를 전파하고
	 * 이미 올라간 객체는 롤백하지 않는다. 디렉터리가 없거나 업로드할 파일이 없으면 StorageException이다.
	 */
	void uploadDirectoryRecursively(Path localDir, String keyPrefix);

	/** 객체를 로컬 경로로 내려받는다. 부모 디렉터리는 생성하고 기존 파일은 덮어쓴다. */
	void downloadObject(String key, Path target);
}
