package com.streaming.music.service;

/**
 * presigned GET URL을 Nginx 내부 경로({@code /internal-storage/...})로 변환한다 (PRD-05 §7.1.2).
 *
 * <p><b>전제(path-style):</b> URL 형식이 {@code {scheme}://{host}[:{port}]/{bucket}/{key}?{query}}이고,
 * endpoint에 경로 접두가 없다. 이 전제가 깨지면(버킷 접두 불일치, 쿼리 없음 등) {@link IllegalStateException}을
 * 던지며 호출 측에서 500으로 처리된다.
 *
 * <p><b>쿼리 보존:</b> 서명이 깨지지 않도록 {@code java.net.URI} 파싱·재조립을 하지 않고 문자열을 그대로 자른다.
 * 쿼리 문자열은 {@code ?}부터 끝까지 한 글자도 바꾸지 않는다. 예외 메시지에는 URL·서명을 담지 않는다.
 */
final class InternalRedirectPathConverter {

	static final String INTERNAL_PREFIX = "/internal-storage/";

	private InternalRedirectPathConverter() {
	}

	/**
	 * @param presignedUrl presigned GET URL 전체
	 * @param bucketName   버킷 이름 (경로 첫 세그먼트)
	 * @return {@code /internal-storage/{key}?{원본 쿼리}}
	 */
	static String toInternalRedirectUri(String presignedUrl, String bucketName) {
		if (presignedUrl == null) {
			throw new IllegalStateException("presigned URL이 비어 있습니다.");
		}
		int schemeEnd = presignedUrl.indexOf("://");
		int pathStart = schemeEnd < 0 ? -1 : presignedUrl.indexOf('/', schemeEnd + 3);
		int queryStart = presignedUrl.indexOf('?');
		if (pathStart < 0 || queryStart < pathStart) {
			throw new IllegalStateException("presigned URL 형식이 예상과 다릅니다.");
		}
		String path = presignedUrl.substring(pathStart, queryStart);
		String bucketPrefix = "/" + bucketName + "/";
		if (!path.startsWith(bucketPrefix) || path.length() == bucketPrefix.length()) {
			throw new IllegalStateException("presigned URL 경로가 버킷 접두와 일치하지 않습니다.");
		}
		return INTERNAL_PREFIX + path.substring(bucketPrefix.length()) + presignedUrl.substring(queryStart);
	}
}
