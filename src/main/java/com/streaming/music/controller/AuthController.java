package com.streaming.music.controller;

import com.streaming.music.dto.response.UserResponse;
import com.streaming.music.global.exception.UnauthorizedException;
import com.streaming.music.global.security.AuthenticatedUser;
import com.streaming.music.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 로그인 사용자 정보 조회 (PRD-05 §7.1).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final UserService userService;

	@GetMapping("/me")
	public UserResponse getCurrentUser(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		// SecurityConfig가 인증을 강제하지만, 방어적으로 null이면 401 처리한다
		if (authenticatedUser == null) {
			throw new UnauthorizedException();
		}
		return UserResponse.from(userService.getUser(authenticatedUser.userId()));
	}
}
