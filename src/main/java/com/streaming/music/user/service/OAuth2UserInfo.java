package com.streaming.music.user.service;

/**
 * OAuth2 로그인에서 추출한 최소 사용자 정보 (sub, email, picture).
 */
public record OAuth2UserInfo(String providerId, String email, String profileImageUrl) {
}
