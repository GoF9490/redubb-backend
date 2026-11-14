package com.streaming.music.repository;

import com.streaming.music.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	/** OAuth2 제공자의 사용자 식별자(sub)로 회원을 조회한다. */
	Optional<User> findByProviderId(String providerId);
}
