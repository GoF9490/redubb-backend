plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.streaming"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // 1. Spring Boot 스타터 (Spring Boot 4 명칭)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 2. 무상태 JWT (JJWT)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // 3. 데이터베이스 (운영: PostgreSQL / 로컬: H2)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    // Boot 4부터 H2 웹 콘솔 자동설정이 별도 모듈로 분리됨 (local 프로파일 /h2-console 용, prod는 enabled 미설정으로 비활성)
    runtimeOnly("org.springframework.boot:spring-boot-h2console")

    // 4. S3 호환 스토리지 클라이언트 (RustFS)
    implementation("io.minio:minio:8.5.14")

    // 5. 음원 분석 및 위변조 검증
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("net.jthink:jaudiotagger:3.0.1")

    // 6. Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // 7. 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
