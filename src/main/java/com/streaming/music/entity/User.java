package com.streaming.music.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 엔티티 (PRD-07 users). Google OAuth2 무가입 방식으로 sub/email/picture만 저장한다.
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_provider_id", columnNames = "provider_id"),
        @UniqueConstraint(name = "uk_users_email", columnNames = "email")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    public static final String PROVIDER_GOOGLE = "GOOGLE";
    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider = PROVIDER_GOOGLE;

    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "role", nullable = false, length = 30)
    private String role = ROLE_USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    @Builder
    private User(String provider, String providerId, String email, String profileImageUrl,
                 String role, Instant createdAt, Instant lastLoginAt) {
        if (provider != null) {
            this.provider = provider;
        }
        this.providerId = providerId;
        this.email = email;
        this.profileImageUrl = profileImageUrl;
        if (role != null) {
            this.role = role;
        }
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastLoginAt == null) {
            lastLoginAt = now;
        }
    }

    /** 로그인 시 최신 이메일/프로필 이미지로 갱신한다. */
    public void updateProfile(String email, String profileImageUrl) {
        this.email = email;
        this.profileImageUrl = profileImageUrl;
    }

    /** 마지막 로그인 시각을 갱신한다. */
    public void updateLastLogin(Instant loginAt) {
        this.lastLoginAt = loginAt;
    }
}
