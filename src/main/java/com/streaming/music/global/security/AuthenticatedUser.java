package com.streaming.music.global.security;

/**
 * JWT 검증을 통과한 사용자. SecurityContext의 Authentication principal로 저장된다.
 * 컨트롤러에서는 {@code @AuthenticationPrincipal AuthenticatedUser}로 받으며, 익명 요청은 null이 주입된다.
 */
public record AuthenticatedUser(Long userId, String role) {
}
