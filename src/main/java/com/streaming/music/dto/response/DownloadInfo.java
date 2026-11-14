package com.streaming.music.dto.response;

/**
 * 원본 다운로드 지시 정보 (PRD-05 §7.1, §7.1.2).
 *
 * <p>본문 없이 헤더로만 내려가며, 값에 presigned 서명 쿼리가 포함되므로 로그에 남기지 않는다.
 *
 * @param xAccelRedirectUri  {@code X-Accel-Redirect} 헤더 값 ({@code /internal-storage/...?서명 쿼리})
 * @param contentDisposition {@code Content-Disposition} 헤더 값 (RFC 5987/6266)
 * @param contentType        {@code Content-Type} 헤더 값 (트랙의 MIME)
 */
public record DownloadInfo(
		String xAccelRedirectUri,
		String contentDisposition,
		String contentType
) {

	/** 서명 쿼리가 로그·예외 메시지로 새지 않도록 redirect 값을 가린다. */
	@Override
	public String toString() {
		return "DownloadInfo[xAccelRedirectUri=****, contentDisposition=" + contentDisposition
				+ ", contentType=" + contentType + "]";
	}
}
