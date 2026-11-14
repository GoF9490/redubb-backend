package com.streaming.music.user.service;

import com.streaming.music.user.entity.User;
import com.streaming.music.global.exception.UnauthorizedException;
import com.streaming.music.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 조회/자동 생성/갱신(Upsert) 서비스 (PRD-05 §6.1).
 *
 * <p>MVP 한계:
 * <ul>
 *   <li>email 변경으로 uk_users_email 유니크 충돌이 나면 DataIntegrityViolationException이 그대로 전파된다.</li>
 *   <li>동일 사용자의 동시 최초 로그인(uk_users_provider_id 위반)도 처리하지 않고 예외를 전파한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

	private final UserRepository userRepository;
	private final Clock clock;

	/**
	 * userId로 회원을 조회한다. 존재하지 않으면(삭제된 회원 등) 401 처리용 예외를 던진다.
	 */
	@Override
	public User getUser(Long userId) {
		return userRepository.findById(userId).orElseThrow(UnauthorizedException::new);
	}

	/**
	 * providerId(sub) 기준으로 회원을 갱신하거나, 없으면 신규 생성한다.
	 */
	@Override
	public User upsertUser(OAuth2UserInfo info) {
		Objects.requireNonNull(info, "info");
		Objects.requireNonNull(info.providerId(), "providerId");
		Objects.requireNonNull(info.email(), "email");

		Instant now = Instant.now(clock);
		return userRepository.findByProviderId(info.providerId())
				.map(user -> {
					// 영속 상태이므로 변경 감지로 반영된다 (save 불필요)
					user.updateProfile(info.email(), info.profileImageUrl());
					user.updateLastLogin(now);
					return user;
				})
				.orElseGet(() -> userRepository.save(User.builder()
						.provider(User.PROVIDER_GOOGLE)
						.providerId(info.providerId())
						.email(info.email())
						.profileImageUrl(info.profileImageUrl())
						.role(User.ROLE_USER)
						.createdAt(now)
						.lastLoginAt(now)
						.build()));
	}
}
