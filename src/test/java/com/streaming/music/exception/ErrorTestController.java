package com.streaming.music.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * GlobalExceptionHandler 검증 전용 컨트롤러. 운영 코드에 섞이지 않도록 src/test에만 둔다.
 */
@RestController
class ErrorTestController {

	static final String SECRET_MARKER = "secret-internal-detail-12345";

	record SampleRequest(@NotBlank String name) {
	}

	@GetMapping("/test/audio-format")
	void throwInvalidAudioFormat() {
		throw new InvalidAudioFormatException();
	}

	@GetMapping("/test/artwork")
	void throwInvalidArtwork() {
		throw new InvalidArtworkException();
	}

	@GetMapping("/test/tracks/{id}")
	String getTrackById(@PathVariable UUID id) {
		return id.toString();
	}

	@GetMapping("/test/not-found")
	void throwTrackNotFound() {
		throw new TrackNotFoundException();
	}

	@GetMapping("/test/not-ready")
	void throwTrackNotReady() {
		throw new TrackNotReadyException();
	}

	@GetMapping("/test/too-large")
	void throwPayloadTooLarge() {
		throw new MaxUploadSizeExceededException(10L);
	}

	@GetMapping("/test/unexpected")
	void throwUnexpected() {
		throw new IllegalStateException(SECRET_MARKER);
	}

	@GetMapping("/test/required-param")
	String getWithRequiredParam(@RequestParam String keyword) {
		return keyword;
	}

	@PostMapping("/test/upload")
	String uploadFile(@RequestPart("file") MultipartFile file) {
		return file.getOriginalFilename();
	}

	@PostMapping("/test/valid")
	String postValidBody(@Valid @RequestBody SampleRequest request) {
		return request.name();
	}
}
