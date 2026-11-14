---
paths:
  - "**/*.java"
---

# Spring Java 코딩 규칙

Spring 환경에서 Java 코드를 작성할 때 아래 규칙을 반드시 지킨다.

## 0. 우선순위
- 이 규칙은 Java 파일을 작성·수정할 때 **최우선**으로 적용한다.
- 다른 규칙(전역/프로젝트 CLAUDE.md, 그 외 rules 파일 등)과 충돌하면, Java 파일 작업 범위 안에서는 이 문서를 따른다.

## 1. 들여쓰기
- 들여쓰기에는 **탭(Tab)**을 사용한다.
- 공백(스페이스)으로 들여쓰지 않는다.

## 2. 서비스 레이어: 인터페이스와 구현 분리
- `@Service` 어노테이션이 붙는 서비스 레이어 코드는 반드시 **인터페이스와 구현 클래스를 분리**한다.
- 구현 클래스 이름은 인터페이스 이름 뒤에 `Impl`을 붙인다. (예: `TrackService`(인터페이스), `TrackServiceImpl`(구현 클래스))
- `@Service`는 구현 클래스에만 붙인다.
- 다른 빈은 구현 클래스가 아니라 인터페이스 타입으로 주입받는다.

```java
// 인터페이스
public interface TrackService {
	TrackResponse getTrack(Long trackId);
}

// 구현 클래스
@Service
@RequiredArgsConstructor
public class TrackServiceImpl implements TrackService {

	private final TrackRepository trackRepository;

	@Override
	public TrackResponse getTrack(Long trackId) {
		// ...
	}
}
```

## 3. 테스트 코드
- 아래 대상의 코드를 작성하거나 수정하면 반드시 **단위 테스트**를 함께 작성한다.
  - **Service**: `@Service`가 붙은 구현 클래스(`XxxServiceImpl`). 테스트 클래스 이름은 `XxxServiceImplTest`로 한다.
  - **Entity**: `entity/` 패키지의 엔티티, Enum, 값 객체. 생성·상태 변경·검증 등 도메인 로직을 테스트한다.
  - **Util**: `global/util/` 등에 있는 유틸 클래스.
- 단위 테스트는 Spring 컨텍스트를 띄우지 않는다. JUnit 5를 쓰고, 의존성이 있으면 Mockito(`@ExtendWith(MockitoExtension.class)`)로 Mock 처리한다.
- 위 대상 **외**의 코드(Controller, Repository, DTO, Config 등)에는 테스트 코드를 작성하지 않는다.
- 테스트 코드 위치는 대상 클래스와 같은 패키지 구조를 따른다. (예: `src/test/java/.../domainA/service/XxxServiceImplTest.java`)
- 단위 테스트 작성과 별개로, **작성한 기능 코드는 한 번씩 직접 검토**한다. (요구사항 충족 여부, 예외 처리, 누락된 분기, 규칙 준수 여부 등)

## 4. 설정값 관리
- 설정값과 기준이 되는 값(URL, 경로, 크기 제한, 만료 시간, 허용 목록 등)은 코드에 하드코딩하지 않는다.
- 이런 값은 `application.yaml`에 작성하고, 코드에서는 이를 참조한다.
  - 관련 값이 여러 개면 `@ConfigurationProperties` 클래스로 묶어 바인딩하는 방식을 우선한다.
  - 민감 정보는 `application.yaml`에서 `${ENV_VAR}` 형태로 환경변수를 참조한다.

## 5. 패키지 구조: 도메인별(package-by-feature)
- 패키지는 레이어가 아니라 **도메인 단위**로 나눈다. 각 도메인 패키지 안에 레이어별 하위 패키지를 둔다.
- 여러 도메인이 함께 쓰는 코드는 `global/`에 둔다.

```
com.example.title
├── global/                  # 공통 (config, exception, security, util, BaseEntity)
├── domainA/
│   ├── controller/
│   ├── service/             # XxxService(인터페이스), XxxServiceImpl(구현)
│   ├── repository/
│   ├── entity/              # Entity, Enum, 값 객체
│   └── dto/
│       ├── request/
│       └── response/
├── domainB/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   └── dto/
└── other/
    └── ...
```
