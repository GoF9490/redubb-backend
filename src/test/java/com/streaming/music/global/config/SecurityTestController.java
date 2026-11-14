package com.streaming.music.global.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SecurityConfig 검증 전용 컨트롤러. 운영 코드에 섞이지 않도록 src/test에만 둔다.
 */
@RestController
class SecurityTestController {

	@GetMapping("/test/ping")
	String getPing() {
		return "pong";
	}

	@PostMapping("/test/ping")
	String postPing() {
		return "pong";
	}
}
