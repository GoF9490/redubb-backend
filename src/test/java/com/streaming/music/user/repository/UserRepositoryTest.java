package com.streaming.music.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles({"local", "test"})
class UserRepositoryTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private UserRepository userRepository;

	@Test
	@DisplayName("providerId로 회원을 조회하고 없으면 빈 값을 반환한다")
	void findByProviderId() {
		entityManager.persist(User.builder().providerId("sub-1").email("a@example.com").build());
		entityManager.flush();
		entityManager.clear();

		assertThat(userRepository.findByProviderId("sub-1"))
				.get().extracting(User::getEmail).isEqualTo("a@example.com");
		assertThat(userRepository.findByProviderId("none")).isEmpty();
	}
}
