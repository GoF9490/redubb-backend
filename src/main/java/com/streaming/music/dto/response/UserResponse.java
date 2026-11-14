package com.streaming.music.dto.response;

import com.streaming.music.entity.User;

/**
 * 로그인 사용자 정보 응답 (PRD-05 §7.1). provider/providerId 등 내부 정보는 노출하지 않는다.
 */
public record UserResponse(Long userId, String email, String profileImageUrl, String role) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getUserId(), user.getEmail(), user.getProfileImageUrl(), user.getRole());
	}
}
