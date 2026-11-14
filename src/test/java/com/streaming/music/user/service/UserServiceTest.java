package com.streaming.music.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.music.user.entity.User;
import com.streaming.music.global.exception.ErrorCode;
import com.streaming.music.global.exception.UnauthorizedException;
import com.streaming.music.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant OLD = Instant.parse("2025-01-01T00:00:00Z");

	@Mock
	private UserRepository userRepository;

	private UserService userService;

	@BeforeEach
	void setUp() {
		userService = new UserService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private User createExistingUser() {
		return User.builder()
				.providerId("sub-1")
				.email("old@example.com")
				.profileImageUrl("http://old/pic.png")
				.createdAt(OLD)
				.lastLoginAt(OLD)
				.build();
	}

	@Test
	@DisplayName("존재하는 userId는 회원을 반환한다")
	void getExistingUser() {
		User user = createExistingUser();
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));

		assertThat(userService.getUser(1L)).isSameAs(user);
	}

	@Test
	@DisplayName("존재하지 않는 userId는 UnauthorizedException(401)을 던진다")
	void throwUnauthorizedWhenUserMissing() {
		when(userRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getUser(99L))
				.isInstanceOf(UnauthorizedException.class)
				.extracting(e -> ((UnauthorizedException) e).getErrorCode())
				.isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	@Test
	@DisplayName("최초 로그인 시 GOOGLE/ROLE_USER 신규 회원이 저장된다")
	void createsNewUserOnFirstLogin() {
		when(userRepository.findByProviderId("sub-1")).thenReturn(Optional.empty());
		when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

		User result = userService.upsertUser(new OAuth2UserInfo("sub-1", "a@example.com", "http://pic/a.png"));

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(captor.capture());
		User saved = captor.getValue();
		assertThat(saved.getProvider()).isEqualTo(User.PROVIDER_GOOGLE);
		assertThat(saved.getRole()).isEqualTo(User.ROLE_USER);
		assertThat(saved.getProviderId()).isEqualTo("sub-1");
		assertThat(saved.getEmail()).isEqualTo("a@example.com");
		assertThat(saved.getProfileImageUrl()).isEqualTo("http://pic/a.png");
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
		assertThat(saved.getLastLoginAt()).isEqualTo(NOW);
		assertThat(result).isSameAs(saved);
	}

	@Test
	@DisplayName("기존 providerId면 중복 저장 없이 email, 프로필 이미지, 마지막 로그인 시각을 갱신한다")
	void updatesExistingUserOnLogin() {
		User existing = createExistingUser();
		when(userRepository.findByProviderId("sub-1")).thenReturn(Optional.of(existing));

		User result = userService.upsertUser(new OAuth2UserInfo("sub-1", "new@example.com", "http://new/pic.png"));

		assertThat(result).isSameAs(existing);
		assertThat(result.getEmail()).isEqualTo("new@example.com");
		assertThat(result.getProfileImageUrl()).isEqualTo("http://new/pic.png");
		assertThat(result.getLastLoginAt()).isEqualTo(NOW);
		assertThat(result.getCreatedAt()).isEqualTo(OLD);
		verify(userRepository, never()).save(any());
	}

	@Test
	@DisplayName("기존 회원의 picture가 null이면 프로필 이미지를 null로 덮어쓴다")
	void overwritesProfileImageWithNull() {
		User existing = createExistingUser();
		when(userRepository.findByProviderId("sub-1")).thenReturn(Optional.of(existing));

		User result = userService.upsertUser(new OAuth2UserInfo("sub-1", "old@example.com", null));

		assertThat(result.getProfileImageUrl()).isNull();
		verify(userRepository, never()).save(any());
	}
}
