package com.streaming.music.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.ActiveProfiles;

/**
 * local 프로파일 설정 검증 (PRD-05 §4.2).
 * 테스트용 더미 시크릿은 application-test.yaml에서 주입한다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ApplicationProfileTest {

	@Autowired
	private Environment environment;

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("local 프로파일은 OSIV를 비활성화한다")
	void openInViewIsDisabled() {
		assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
		assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
	}

	@Test
	@DisplayName("local 프로파일은 H2 인메모리 DB(streaming_db, PostgreSQL 모드)와 ddl-auto=update를 사용한다")
	void localDatasourceSettings() {
		assertThat(environment.getProperty("spring.datasource.url"))
				.contains("jdbc:h2:mem:streaming_db")
				.contains("MODE=PostgreSQL");
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
	}

	@Test
	@DisplayName("local 프로파일은 H2 콘솔 설정을 활성화한다")
	void h2ConsoleProperties() {
		assertThat(environment.getProperty("spring.h2.console.enabled", Boolean.class)).isTrue();
		assertThat(environment.getProperty("spring.h2.console.path")).isEqualTo("/h2-console");
	}

	@Test
	@DisplayName("local 프로파일에서 /h2-console/* 서블릿이 실제로 등록된다")
	void h2ConsoleServletIsRegistered() {
		ServletRegistrationBean<?> registration = context.getBean("h2Console", ServletRegistrationBean.class);
		assertThat(registration.getUrlMappings()).contains("/h2-console/*");
	}

}
