package com.streaming.music.track.dto.request;

import org.springframework.web.multipart.MultipartFile;

/**
 * 음원 업로드 요청 (PRD-05 §7.1.1, multipart/form-data).
 *
 * @param file        음원 파일 (필수)
 * @param title       사용자가 확인·수정한 제목 (선택)
 * @param artist      사용자가 확인·수정한 가수 (선택)
 * @param album       사용자가 확인·수정한 앨범 (선택)
 * @param artworkFile 사용자가 직접 첨부한 앨범자켓 (선택, JPEG/PNG)
 */
public record TrackUploadRequest(
		MultipartFile file,
		String title,
		String artist,
		String album,
		MultipartFile artworkFile
) {
}
