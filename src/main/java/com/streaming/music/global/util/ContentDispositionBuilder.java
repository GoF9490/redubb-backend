package com.streaming.music.global.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 다운로드용 {@code Content-Disposition} 헤더 값을 만든다 (RFC 6266, RFC 5987).
 *
 * <p>형식: {@code attachment; filename="<ASCII 대체>"; filename*=UTF-8''<퍼센트 인코딩>}
 *
 * <p><b>정리 규칙(헤더 인젝션 방지):</b>
 * <ol>
 *   <li>CR/LF를 포함한 제어문자(0x00~0x1F, 0x7F)를 제거한다.</li>
 *   <li>마지막 {@code /} 또는 {@code \} 앞부분(경로)을 제거한다.</li>
 *   <li>255자로 절단한다. 결과가 비어 있으면 기본 이름({@code track-{uuid}.{ext}})을 쓴다.</li>
 * </ol>
 *
 * <p><b>ASCII 대체:</b> 0x20~0x7E 이외의 문자와 {@code " \ ; %}는 {@code _}로 치환한다.
 * 확장자 앞 본문에 영문·숫자가 하나도 남지 않으면(예: 전부 한글이던 이름) 의미 없는 {@code __.mp3} 대신
 * 기본 이름을 쓴다. {@code filename*}에는 RFC 5987 attr-char 외 모든 바이트를 {@code %XX}(대문자)로 인코딩한다.
 * {@code URLEncoder}는 공백을 {@code +}로 인코딩하므로 쓰지 않는다.
 */
public final class ContentDispositionBuilder {

	static final int FILENAME_MAX_LENGTH = 255;

	private static final String ATTR_CHAR_SYMBOLS = "!#$&+-.^_`|~";
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private ContentDispositionBuilder() {
	}

	public static String build(String originalFilename, UUID trackId, String fileExtension) {
		String extensionPart = fileExtension == null || sanitizeExtension(fileExtension).isEmpty()
				? "" : "." + sanitizeExtension(fileExtension);
		String defaultName = "track-" + trackId + extensionPart;
		String name = sanitizeFilename(originalFilename);
		if (name.isEmpty()) {
			name = defaultName;
		}
		String asciiName = toAsciiFallback(name);
		if (!hasMeaningfulStem(asciiName)) {
			asciiName = defaultName;
		}
		return "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodeRfc5987(name);
	}

	private static String sanitizeExtension(String extension) {
		return extension.replaceAll("[^A-Za-z0-9]", "");
	}

	private static String sanitizeFilename(String filename) {
		if (filename == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder(filename.length());
		for (int i = 0; i < filename.length(); i++) {
			char c = filename.charAt(i);
			if (c >= 0x20 && c != 0x7F) {
				builder.append(c);
			}
		}
		String name = builder.toString();
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).strip();
		if (name.length() > FILENAME_MAX_LENGTH) {
			name = name.substring(0, FILENAME_MAX_LENGTH);
		}
		return name;
	}

	private static String toAsciiFallback(String name) {
		StringBuilder builder = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean safe = c >= 0x20 && c <= 0x7E && c != '"' && c != '\\' && c != ';' && c != '%';
			builder.append(safe ? c : '_');
		}
		return builder.toString();
	}

	/** 마지막 '.' 앞 본문에 영문·숫자가 하나라도 있는지 확인한다(점으로 시작하는 이름은 전체를 본문으로 본다). */
	private static boolean hasMeaningfulStem(String asciiName) {
		int dot = asciiName.lastIndexOf('.');
		String stem = dot > 0 ? asciiName.substring(0, dot) : asciiName;
		for (int i = 0; i < stem.length(); i++) {
			char c = stem.charAt(i);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				return true;
			}
		}
		return false;
	}

	private static String encodeRfc5987(String value) {
		StringBuilder builder = new StringBuilder();
		for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
			int unsigned = b & 0xFF;
			if (isAttrChar(unsigned)) {
				builder.append((char) unsigned);
			} else {
				builder.append('%').append(HEX[unsigned >> 4]).append(HEX[unsigned & 0x0F]);
			}
		}
		return builder.toString();
	}

	private static boolean isAttrChar(int c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
				|| (c < 0x80 && ATTR_CHAR_SYMBOLS.indexOf(c) >= 0);
	}
}
