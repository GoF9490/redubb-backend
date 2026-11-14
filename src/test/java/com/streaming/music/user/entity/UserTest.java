package com.streaming.music.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User 엔티티 단위 테스트 (순수 자바).
 */
class UserTest {

	private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant FIRST_LOGIN = Instant.parse("2026-01-01T00:00:00Z");

	private User newUser() {
		return User.builder()
				.providerId("sub-1").email("old@example.com").profileImageUrl("http://pic/old.png")
				.createdAt(CREATED).lastLoginAt(FIRST_LOGIN).build();
	}

	@Test
	@DisplayName("재로그인 시 이메일과 프로필 이미지를 최신 값으로 갱신한다")
	void updatesEmailAndProfileImageOnRelogin() {
		User user = newUser();

		user.updateProfile("new@example.com", "http://pic/new.png");

		assertThat(user.getEmail()).isEqualTo("new@example.com");
		assertThat(user.getProfileImageUrl()).isEqualTo("http://pic/new.png");
	}

	@Test
	@DisplayName("재로그인 시 마지막 로그인 시각을 갱신한다")
	void updatesLastLoginAtOnRelogin() {
		User user = newUser();
		Instant relogin = Instant.parse("2026-02-01T00:00:00Z");

		user.updateLastLogin(relogin);

		assertThat(user.getLastLoginAt()).isEqualTo(relogin);
	}

	@Test
	@DisplayName("재로그인 갱신은 가입 시각과 식별자를 바꾸지 않는다")
	void keepsCreatedAtOnRelogin() {
		User user = newUser();

		user.updateProfile("new@example.com", "http://pic/new.png");
		user.updateLastLogin(Instant.parse("2026-02-01T00:00:00Z"));

		assertThat(user.getCreatedAt()).isEqualTo(CREATED);
		assertThat(user.getProviderId()).isEqualTo("sub-1");
	}

	@Test
	@DisplayName("프로필 이미지가 null이면 기존 이미지를 null로 덮어쓴다")
	void overwritesProfileImageWithNull() {
		User user = newUser();

		user.updateProfile("old@example.com", null);

		assertThat(user.getProfileImageUrl()).isNull();
	}

	@Test
	@DisplayName("공급자와 권한을 지정하지 않으면 GOOGLE과 ROLE_USER로 생성된다")
	void defaultsProviderAndRoleWhenOmitted() {
		User user = newUser();

		assertThat(user.getProvider()).isEqualTo(User.PROVIDER_GOOGLE);
		assertThat(user.getRole()).isEqualTo(User.ROLE_USER);
	}

	@Test
	@DisplayName("저장 직전 콜백은 가입·로그인 시각이 없으면 현재 시각으로 채운다")
	void fillsTimestampsOnPrePersistWhenMissing() {
		User user = User.builder().providerId("sub-2").email("a@example.com").build();
		Instant before = Instant.now();

		user.prePersist();

		assertThat(user.getCreatedAt()).isBetween(before, Instant.now());
		assertThat(user.getLastLoginAt()).isBetween(before, Instant.now());
	}

	@Test
	@DisplayName("저장 직전 콜백은 이미 지정된 시각을 덮어쓰지 않는다")
	void keepsProvidedTimestampsOnPrePersist() {
		User user = newUser();

		user.prePersist();

		assertThat(user.getCreatedAt()).isEqualTo(CREATED);
		assertThat(user.getLastLoginAt()).isEqualTo(FIRST_LOGIN);
	}
}
