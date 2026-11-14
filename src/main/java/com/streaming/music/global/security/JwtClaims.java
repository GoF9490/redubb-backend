package com.streaming.music.global.security;

/**
 * 검증을 통과한 JWT에서 추출한 클레임 (sub=userId, role).
 */
public record JwtClaims(Long userId, String role) {
}
