package com.streaming.music.user.service;

import com.streaming.music.user.entity.User;

/**
 * 회원 조회/자동 생성/갱신(Upsert) 서비스 (PRD-05 §6.1).
 */
public interface UserService {

	/**
	 * userId로 회원을 조회한다. 존재하지 않으면(삭제된 회원 등) 401 처리용 예외를 던진다.
	 */
	User getUser(Long userId);

	/**
	 * providerId(sub) 기준으로 회원을 갱신하거나, 없으면 신규 생성한다.
	 */
	User upsertUser(OAuth2UserInfo info);
}
