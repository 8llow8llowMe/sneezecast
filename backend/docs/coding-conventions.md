# 백엔드 코딩 컨벤션

이 문서가 백엔드 코드 규칙의 정본이다. 계층·모듈 구조는 [architecture-guide.md](architecture-guide.md), 모듈 목록은 [modules.md](modules.md) 를 따른다.
hondigagae 백엔드 컨벤션에서 도메인과 무관한 규칙을 옮겨 왔다.

## 1. 포맷

- **한 줄 180자 하드랩.** 180자 이하면 한 줄로 둔다. 넘으면 의미 단위로 묶어 줄바꿈하고, 파라미터를 한 줄에 하나씩 세우지 않는다.
- Swagger `@Parameter` + `@RequestParam` 조합도 한 줄에 둔다.
- 모든 파일은 UTF-8 (no BOM), LF.

## 2. 타입

- 기본은 primitive. Wrapper 는 null 이 의미를 가질 때(선택 필터, "미설정" 과 기본값 구분)만 쓴다.
- 단순 조회는 개별 파라미터. 조건이 4개 이상이거나 `filter + sort + cursor + size` 처럼 함께 움직이면 `*Criteria` / `*Query` 로 묶는다.

### 2-1. 식별자

클라이언트로 나가는 **모든 식별자는 `String`** 이다. Snowflake ID(19자리)가 JS `Number.MAX_SAFE_INTEGER`(16자리)를 넘어 정밀도가 깨지기 때문이다. Snowflake 가 아닌 ID 도 통일한다.

| 위치 | 타입 |
|------|------|
| response / item DTO 의 `*Id` | `String` |
| `@PathVariable` / `@RequestParam` | `long` |
| request DTO | `Long` |
| Info · domain model | `long` |

- 변환 경계는 Presenter 다. nullable ID 는 null 을 유지한다.
- `@Schema(example = "...")` 는 따옴표 문자열로 쓴다.

## 3. 네이밍

| 역할 | 접미어 |
|------|--------|
| 외부 API 컨트롤러 | `*WebController` |
| 유스케이스 (in-port) | `*WebUseCase` / `*InternalUseCase` |
| 오케스트레이션 | `*WebFacade` / `*InternalFacade` |
| 비즈니스 로직 | `*Processor` |
| 응답 변환 | `*Presenter` |
| Feign 인터페이스 | `*Client` |
| 복잡 조회 결과 | `*QueryResult` |
| 조회 조건 묶음 | `*Criteria` / `*Query` |

포트는 **책임으로** 이름 짓고 전송 기술을 쓰지 않는다.

| 대상 | 포트 | 어댑터 |
|------|------|--------|
| 다른 서비스 (Feign) | `*QueryPort` / `*CommandPort` | `*ClientAdapter` |
| DB (JPA) | `*RepositoryPort` | `*RepositoryAdapter` / `*PersistenceAdapter` |
| JDBC 대량 쓰기 | `*BulkPort` | `*BulkAdapter` |
| 인프라 (캐시·스토리지·메시징) | 도메인 의미로 (`ReportCachePort` 등) | `*Adapter` |

Feign 전용 응답 DTO 는 `*ClientResponse` 로 짓고 `adapter/out/client/feign/dto` 에 둔다.

## 4. 매핑

- Entity ↔ Domain 은 MapStruct. `Info → Response` 는 Presenter 가 맡는다.
- Processor 는 Response DTO 를 만들지 않는다.

## 5. Swagger · DTO

- `@Tag` / `@Operation` / `@Parameter` / `@Schema` 를 쓰고 설명은 한국어로 쓴다. 인증 API 는 `@SecurityRequirement`, 내부 API 는 `@Hidden` 을 검토한다.
- record DTO 는 `@Schema` 를 component 바로 위 줄에 두고 component 사이에 빈 줄을 둔다. 검증 어노테이션은 `@Schema` 다음 줄. `@Builder` 와 record 선언 사이에는 빈 줄을 두지 않는다.

```java
@Builder
public record WeeklyReportRequest(
    @Schema(description = "보고 행정동 코드 (SGIS 8자리). 보고 주는 서버가 정한다", example = "11240660")
    @NotBlank(message = ReportValidationMessage.DISTRICT_CODE_REQUIRED)
    String districtCode,

    @Schema(description = "증상군 코드 목록. 증상 없음이면 빈 목록")
    @NotNull(message = ReportValidationMessage.SYMPTOMS_REQUIRED)
    List<@NotNull(message = ReportValidationMessage.SYMPTOM_ITEM_REQUIRED) SymptomGroup> symptoms
) {
}
```

## 6. 예외

### 6-1. 3종 세트

도메인마다 아래 세 개를 둔다. `BadRequestException` 같은 공통 예외를 쓰지 않는다.

| 클래스 | 위치 | 내용 |
|--------|------|------|
| `{Domain}ErrorCode` | `application/exception` | enum, 필드는 `code` · `message` · `HttpStatus` 3개로 고정 |
| `{Domain}Exception` | `application/exception` | `RuntimeException`, ErrorCode 를 받는다 |
| `{Domain}ExceptionHandler` | `adapter/in/web/exception` | `@RestControllerAdvice(basePackages = ...)`, `Response.fail()` 로 변환 |

- 상세 정보는 메시지의 `(%s)` 자리 + `Object... args` 로 채운다. `(errorCode, String detail)` 처럼 임의 문자열을 잇는 생성자를 만들지 않는다.
- advice 가 둘 이상이면 `@Order` 를 준다.

### 6-2. 에러코드 대역

`{DOMAIN}_400` 처럼 뭉뚱그리지 않는다.

| 대역 | 용도 |
|------|------|
| `001~099` | 비즈니스 오류 |
| `100` | 역직렬화 실패 등 요청 폴백 (`INVALID_REQUEST`) |
| `101~` | 필드별 검증 오류 |
| 대역 끝 | 프레임워크 2종 — `PARAMETER_TYPE_INVALID`, `PARAMETER_REQUIRED` |

- 필드별 코드는 `{Domain}ValidationMessage` 상수 클래스에 `"CODE:메시지"` 형식으로 모으고, ErrorCode enum 에 중복 정의하지 않는다.
- 검증 예외 → 응답 변환은 `common-core` 의 `ValidationErrorSupport` 에 위임한다.
- 한 필드에 오류가 여럿이면 필드 순서 → 제약 우선순위(필수 → 길이 → 범위 → 형식) → 메시지 순으로 정렬한다.
- 같은 의미를 두 제약으로 중복 검사하지 않는다. 컬렉션 원소는 `@NotNull` 과 값 제약을 쌍으로 걸고, `.filter(Objects::nonNull)` 로 조용히 접지 않는다.

### 6-3. 상태값

반복되는 상태·구분 값은 enum 으로 정의하고 응답에는 `enum.name()` 을 쓴다.

## 7. enum metadata

화면에 보여 줄 enum 은 `{code, name, description}` 객체로 내린다. raw enum 문자열만 내려 FE 가 한국어 매핑 테이블을 갖게 하지 않는다.

- enum 필드명은 `displayName` · `description` · `sortOrder` 로 통일한다. `code` 는 `name()` 으로 부족할 때만 추가한다.

## 8. 영속성

### 8-1. 엔티티

- **JPA 연관관계 어노테이션 금지** — `@ManyToOne` / `@OneToMany` / `@OneToOne` / `@ManyToMany` / `@JoinColumn` / `@JoinTable`. raw FK 컬럼만 두고, 객체 그래프는 application 계층에서 별도 조회한다.
- 모든 컬럼에 `@Comment`. FK 는 `@Comment("회원 아이디 (FK: member.id)")` 형식으로 대상 테이블을 적는다.
- 단일 PK 를 우선한다. N:N 은 중간 테이블로 푼다.
- 엔티티의 PK · FK 는 Wrapper, 카운트 · boolean 은 primitive. 도메인 모델의 PK · FK 는 primitive (nullable 만 Wrapper).
- **enum 컬럼은 `@Enumerated(EnumType.STRING)` 과 `@JdbcTypeCode(SqlTypes.VARCHAR)` 를 함께** 건다. 앞의 것만 있으면 Hibernate 6 가 MySQL · H2 에 네이티브 `enum(...)` 컬럼을 만들어, 값 추가 때마다 ALTER 가 필요하고 dev 의 `ddl-auto: update` 로는 반영되지 않는다. 엔티티 스키마 테스트로 VARCHAR 인지 고정한다.
- **Snowflake 로 ID 를 미리 정하는 엔티티는 `Persistable<Long>` 을 구현한다** — `isNew()` = `createdAt == null`. 구현하지 않으면 Spring Data 가 ID 가 있는 엔티티를 기존 행으로 보고 `save` 를 `merge` 로 보내, INSERT 전에 SELECT 가 한 번 더 나가고 **ID 가 겹치면 기존 행을 조용히 덮어쓴다**. 이 판정 때문에 **기존 행 수정은 "조회한 엔티티를 바꿔 변경 감지" 또는 갱신 쿼리로 한다** — 도메인 모델에서 새로 매핑한 엔티티를 `save` 하면 INSERT 로 가서 PK 위반이 난다. (현재 auth 엔티티에 각각 구현, `BaseEntity` 로 올리는 것은 후속 이슈)

### 8-2. 느슨한 결합 — DB FK 제약을 두지 않는다

서비스마다 스키마가 따로라서 **서비스 사이에는 DB 외래키를 걸 수 없다.** 같은 스키마 안에서도 FK 제약을 두지 않고 규칙을 하나로 맞춘다.

- 다른 테이블 · 다른 서비스의 엔티티는 **ID 값(raw 컬럼)으로만** 참조한다. 연관관계 어노테이션을 쓰지 않으므로 JPA 가 FK 제약을 만들지 않는다.
- FK 로 쓰는 컬럼에는 조회용 인덱스를 직접 건다 (제약이 없으니 인덱스도 자동으로 생기지 않는다).
- 참조 무결성은 애플리케이션이 지킨다.
  - 같은 서비스: 저장 전에 대상 존재를 확인하고, 삭제 시 참조하는 행을 같은 트랜잭션에서 정리한다.
  - 다른 서비스: 저장 시 내부 API 로 검증하고, 원본이 사라지면 원본 서비스가 내부 API 로 정리를 알린다 (예: 탈퇴 → 원시 보고 파기). 존재하지 않는 ID 를 받았을 때의 동작(무시·404)을 API 마다 정한다.
- 다른 서비스 데이터가 화면에 필요하면 조인이 아니라 벌크 내부 API 로 가져온다 (§8-5 N+1).

### 8-3. 인덱스

- `idx_{table}_{col1}_{col2}...` / 유니크는 `uk_{table}_{...}`. 컬럼은 snake_case 전체 이름을 쓰고, MySQL 64자 제한을 넘을 때만 줄인다.

### 8-4. 쿼리 수단 순서

1. 파생 쿼리
2. 정적 JPQL `@Query`
3. **동적 조건 · 조인은 QueryDSL** — `repository/custom` 에 `*CustomRepository` + `Impl`
4. 배치 대량 쓰기만 JDBC
5. **네이티브 쿼리는 쓰지 않는다**

- `JPAQueryFactory` 는 `persistence-core` 설정을 `@Import` 해서 얻는다. `@DataJpaTest` 에도 `@Import` 한다.
- `@Param` 을 쓰지 않는다 (`-parameters` 컴파일 옵션에 의존).
- 커스텀 리포지터리는 컴파일로 검증되지 않으므로 H2 슬라이스 테스트로 실제 스키마에 질의한다.

### 8-5. N+1 금지

- 루프 · 스트림 안에서 `Port.` / `Repository.` 단건 호출을 하지 않는다. DB 는 `in` 절 벌크 조회, 다른 서비스는 벌크 내부 엔드포인트로 바꾼다.
- 원천 단위가 원래 반복이라 불가피하면 **이유를 주석으로 남긴다.**

## 9. 서비스 간 호출 (Feign)

- `name` 은 `"${feign-client.target-services.<논리명>:<논리명>}"` 형식. Eureka 등록명이 환경마다 다를 수 있어 서비스명을 하드코딩하지 않는다.
- 같은 대상을 여러 인터페이스가 부르면 `contextId` 필수 (빈 이름 충돌).
- `url` 과 per-client `configuration` 은 기본으로 붙이지 않는다. timeout 은 `spring.cloud.openfeign.client.config` 공통 설정으로 관리한다.
- Resilience4j 설정은 `application.yml` 의 `configs.default` / `instances.<논리명>`. 서킷 인스턴스명은 대상 논리명.
- 서킷 · 예외 변환은 공통 헬퍼 `requestAndUnwrap(대상, Supplier)` 에서. 5xx · 타임아웃만 집계하고 4xx 는 `ignore-exceptions` 로 뺀다. `FeignException` · `CallNotPermittedException` 은 상위로 흘리지 않고 `INTERNAL_SERVICE_UNAVAILABLE`(503) 도메인 예외로 바꾼다.
- **모든 외부 호출(다른 서비스 · 외부 API)에 connect / read timeout 을 명시한다.**
- 한 서비스에 같은 단순 이름의 `@Component` 가 두 패키지에 있으면 기동이 실패한다. 컨텍스트가 여럿이면 클래스명에 접두사를 붙이고, `*ApplicationTests`(컨텍스트 로딩)로 확인한다.

## 10. 로그

- 검색 가능한 영어 key=value 로 쓴다. 사용자 노출 메시지와 내부 로그 메시지를 분리한다.
- **증상 보고 내용, 회원 식별정보, 토큰은 로그에 남기지 않는다.** 필요하면 가명 ID 만 남긴다.
- **비밀값(서명 키 · pepper · 비밀번호)을 담는 설정 record 는 `toString()` 에서 값을 가린다**(`****`, 길이도 노출하지 않음). 검증 실패 메시지에도 값 대신 설정 키 이름과 환경변수 이름만 넣는다.
