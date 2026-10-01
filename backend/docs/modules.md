# 백엔드 모듈 구조

모듈 구성과 각 모듈에 **무엇을 넣고 무엇을 넣지 않는지**의 정본이다. 계층·호출 규칙은 [architecture-guide.md](architecture-guide.md).

## 전체 구조

```text
backend/
├── core/            라이브러리 (jar, bootJar off)
│   ├── common-core          응답 봉투, 검증 오류 변환, Swagger·Jasypt 공통
│   ├── persistence-core     JPA Auditing, QueryDSL, Snowflake ID
│   ├── redis-core           Redis Sentinel 설정
│   ├── security-core        JWT 발급·검증, 역할·scope 해석, 인증 오류 응답
│   └── storage-core         MinIO 오브젝트 스토리지 (프로필 이미지)
├── cloud/           실행 모듈 (bootJar on)
│   ├── service-discovery    Eureka 서버
│   └── api-gateway          Spring Cloud Gateway
└── service/         도메인 서비스 (bootJar on)
    ├── auth-service         회원·인증·동의·행정동 설정·알림 발송
    ├── surveillance-service 행정동·주간 보고·집계·운영자 검토·안내 발행·공식 자료
    └── batch-service        외부 참조 데이터 적재 (Quartz + Spring Batch)
```

- 패키지 루트는 `com.sneezecast`. Java 21, Spring Boot 3.4.x, Spring Cloud 2024.0.x.
- `core` / `cloud` / `service` 그룹 프로젝트 자체는 bootJar 를 끈다. 빠뜨리면 루트 `./gradlew build` 가 main class 없이 실패한다.

### 두지 않는 모듈

| 모듈 | 이유 | 다시 검토하는 시점 |
|------|------|---------------------|
| `shared-*` 공유 도메인 모듈 | 서비스를 가로지르는 개념이 행정동 코드 하나뿐이고, 그 검증은 surveillance API 가 정본이다 | 두 서비스가 같은 enum 을 쓰게 될 때 (`core/shared-surveillance`) |
| `ai-service` | LLM 은 안내문 초안 한 곳에서만 쓴다. surveillance 의 out-port 로 둔다 | AI 사용처가 여럿으로 늘 때 |

---

## core/common-core

도메인에 비의존적인 범용 인프라.

- `dto.Response<T>` / `DataHeader` — 공통 응답 봉투
- `dto.ValidationErrorItem`, `exception.ValidationErrorSupport` — 검증 예외 → `fieldErrors` 변환 (이 클래스만 `fieldErrors` 를 만든다)
- `dto.metadata.*` — enum `{code, name, description}` metadata
- `config.*` — Swagger 공통, Jasypt

**넣지 않는 것**: 증상군·행정동 같은 도메인 개념.

## core/persistence-core

- `entity.BaseEntity` — `createdAt` / `updatedAt` Auditing
- `config.QuerydslConfigurer` — `JPAQueryFactory` 빈
- `config.SnowflakeConfigurer`, `util.SnowflakeIdGenerator`
- `dto.SliceResponse` — 무한 스크롤 응답
- 스키마는 DB 담당자가 직접 관리한다 (마이그레이션 도구 없음). `ddl-auto` 는 dev `update`, prod `none` — 애플리케이션이 운영 스키마를 바꾸지 않는다. prod 에 필요한 DDL 은 PR 본문에 적어 담당자에게 넘긴다.

## core/redis-core

- Redis Sentinel 연결 설정 (팀 인프라 3노드 센티널). 설정이 빠지면 기동에서 실패시킨다.
- 명령 timeout `infra.redis.command-timeout` 기본 1초 (0 이하는 기동 실패). Lettuce 기본값 60초로 두면 페일오버 동안 호출자가 통째로 멈춘다.
- 사용처: auth-service(세션 · OAuth state · access token 블랙리스트 쓰기), api-gateway(블랙리스트 조회). 두 앱의 `REDIS_KEY_PREFIX` 는 같아야 한다.

## core/security-core

- `auth` 패키지 — JWT 발급·파싱(`JwtAuthProvider`), auth-service 용 필터 체인·비밀번호 인코더, access token 블랙리스트 계약(`AccessTokenBlacklistVerifier`, 구현은 auth-service)
- `resourceserver` 패키지 — 서비스 측 JWT 검증(Resource Server), `SecurityFilterChain` 기본 구성. claim 이 규약과 다른 토큰은 401 `TOKEN_INVALID` 로 거부한다 (auth 쪽과 같은 판정). 사용처: surveillance-service (검증 키 `app.security.jwt.resource.access-key`)
- 역할 `USER`(일반 회원) / `OPERATOR`(검토·안내 발행) / `ADMIN`(관리자 페이지 — 회원·역할 부여, 운영 설정, 참조 데이터 수동 적재. 운영 API 도 허용), scope claim 해석 (`report:write` — 민감정보 동의를 마친 회원에게만 발급)
  - authority 는 역할 이름 그대로(`ROLE_` 접두어 없음) + scope 마다 `SCOPE_<scope>`. 검사는 `hasAuthority('OPERATOR')`, `hasAuthority(SecurityScope.REPORT_WRITE_AUTHORITY)` 로 한다 — `hasRole(...)` 은 동작하지 않는다.
  - scope 문자열·claim 이름은 `SecurityScope` 한 곳에만 둔다.
- 서명 키를 담는 설정 record(`JwtAuthProperties` · `JwtResourceServerProperties`)가 HS512 키 길이(UTF-8 64바이트)를 생성 시점에 검사하고 `toString()` 에서 키를 가린다. 공통 규칙은 `common.jwt.JwtSigningKeys`. 키를 바이트로 바꿀 때는 항상 UTF-8 을 명시한다 — 발급과 검증의 바이트 해석이 어긋나면 모든 토큰이 401 이 된다.
- 인증·인가 실패를 `Response` 봉투로 쓰는 오류 writer

**넣지 않는 것**: 발급 **정책**(언제 어떤 역할·scope 를 싣는지 — auth-service 소유), 회원 조회.

## core/storage-core

- MinIO(S3 호환) 클라이언트, 버킷 초기화, 업로드 · 삭제 · 공개 URL 조립
- 삭제는 DB 커밋 이후로 미룬다 (`deleteAfterCommit`). 롤백됐는데 파일만 지워지는 일을 막는다.
- 이미지 검증 (형식 · 크기)
- DB 에는 URL 이 아니라 **오브젝트 키**를 저장한다. 버킷·도메인이 바뀌어도 데이터를 고치지 않는다.
- 사용처는 현재 auth-service(프로필 이미지)뿐이다. **증상·건강 관련 파일은 올리지 않는다.**

---

## cloud/service-discovery

Eureka 서버. 서비스는 `@EnableDiscoveryClient` 로 등록하고, 게이트웨이·Feign 은 `lb://<서비스명>` 으로 찾는다. batch-service 도 등록한다.

## cloud/api-gateway

- 라우팅, CORS, JWT 1차 검증. 토큰이 없는 요청은 통과시키고 권한 판단은 각 서비스가 한다.
- JWT 는 **서명 · 만료 · 폐기(블랙리스트)만** 본다. `role` · `scope` 는 해석하지 않는다 — 역할 목록을 게이트웨이에 복사하면 역할이 늘 때 게이트웨이만 뒤처진다. claim 규약 위반 거부는 서비스 Resource Server(`JwtToMemberConverter`)가 한다.
- **`/internal/**` 은 라우팅하지 않는다.** 라우트 커버리지 테스트(`GatewayRouteCoverageTest`)로 막는다. 모든 공개 경로는 `/api/v1/` 로 시작한다.
- **JWT 거부 응답**은 `Response` 봉투를 쓴다 (그 밖의 게이트웨이 오류는 아직 Spring 기본 형식). 에러 코드는 security-core `SecurityErrorCode` 와 같은 문자열이다 (계약 테스트로 고정).
- CORS 허용 오리진은 security-core `AuthSecurityConfigurer` 와 같은 목록이다.
- `JWT_ACCESS_KEY` 가 UTF-8 64바이트 미만 · 공백이면 기동 실패 (`JwtVerificationProperties`). 게이트웨이는 security-core 에 의존하지 않아 같은 규칙을 이 record 에 따로 둔다 — 규칙을 바꾸면 security-core `JwtSigningKeys` 와 함께 고친다.

| 경로 | 서비스 |
|------|--------|
| `/api/v1/auth/**`, `/api/v1/members/**` | auth-service |
| `/api/v1/districts/**`, `/api/v1/reports/**`, `/api/v1/advisories/**` | surveillance-service |

- 라우트는 dev · prod 가 같다. 새 공개 경로는 해당 기능 이슈에서 이 표와 함께 추가한다.
- 설정은 dev / prod 프로필만 두고(로컬 프로필 없음) 값은 환경변수로 받는다. Swagger 집계는 아직 두지 않는다.

**JWT 필터 규칙**

- `Authorization` 헤더가 **없을 때만** 비로그인으로 통과시킨다. 헤더가 있는데 `Bearer <token>` 이 아니면(scheme 은 대소문자 무시) 401 `SECURITY_005` 로 거부한다 — 서비스 쪽 토큰 리졸버는 대소문자를 가리지 않아, 게이트웨이가 통과시키면 블랙리스트를 우회한다.
- `jti` 가 없는 토큰은 폐기할 수 없으므로 401 `SECURITY_003` 으로 거부한다.
- 블랙리스트 조회는 이벤트 루프 밖(`boundedElastic`)에서 한다.
- 필터 순서는 `HIGHEST_PRECEDENCE + 1` 로 고정한다 (로드밸런서 · 라우팅 필터보다 앞). WebSocket 라우팅은 쓰지 않으므로 끈다.
- 클라이언트가 보낸 회원 헤더(`X-Authenticated-Member-Id`, `X-Member-Id`)는 지우고 `sub` 로 다시 붙인다.

**그 밖의 설정**: 응답 CORS 헤더 중복 제거(`DedupeResponseHeader`, auth-service 도 CORS 를 붙이므로), 업스트림 connect 2초 · response 10초 (초과 시 504, 봉투 아님).

---

## service/auth-service

| 컨텍스트 | 책임 |
|----------|------|
| `auth` | 이메일 + 비밀번호 가입·로그인, 카카오 소셜 로그인, 이메일 인증 코드, 비밀번호 재설정, 토큰 발급·재발급·폐기, 세션(기기) 관리 |
| `member` | 회원 (닉네임·프로필 이미지), 내 정보 수정, 비밀번호 변경·설정, 탈퇴 |
| `consent` | 민감정보 처리 동의와 철회 이력 (알림 수신 동의는 2단계) |
| `region` | 회원이 선택한 행정동 |
| `notification` | PWA 푸시 구독, 안내 발행 시 팬아웃 발송, 발송 로그 (2단계) |

- 스키마: MySQL `auth` (물리 DB 이름 `sneezecast_auth` — 공유 dev MySQL 에서 다른 프로젝트와 겹치지 않게 접두사를 붙인다). Redis · MinIO 사용.
- **증상 보고를 저장하지 않는다.**
- hondigagae auth-service 구조를 따른다 (반려견 `pet` 컨텍스트와 네이버 로그인은 제외).

**API** (`/api/v1/auth`, `/api/v1/members`)

| 구분 | API |
|------|-----|
| 가입 | `POST /email/send-code` · `POST /email/verify-code` · `POST /signup` |
| 로그인 | `POST /login` · `GET /{provider}/authorize` · `GET /{provider}/login` · `POST /token/reissue` · `POST /logout` |
| 비밀번호 | `POST /password/reset/send-code` · `POST /password/reset` · `POST /me/password` (변경) · `POST /me/password/setup` (소셜 가입자 최초 설정) |
| 세션 | `GET /sessions` · `DELETE /sessions/{sessionId}` |
| 내 정보 | `GET /me` · `PATCH /me` · `DELETE /me/profile-image` · `POST /me/withdraw` |

**저장소**

| 위치 | 대상 |
|------|------|
| MySQL `member` | 이메일(unique) · 비밀번호 해시 · 닉네임 · 프로필 이미지(소셜 URL / 업로드 오브젝트 키) · 역할 · 소셜 제공자 · 상태 · 탈퇴 시각 |
| MySQL `member_consent` | 동의 종류 · 문서 버전 · 동의/철회 이력 |
| MySQL `member_region` | 선택한 행정동 (회원당 1개) |
| MySQL `report_purge_request` | 탈퇴 · 건강정보 동의 철회 시 surveillance 원시 보고 파기 요청 (완료될 때까지 재시도) |
| Redis (TTL) | 이메일 인증 코드, 비밀번호 재설정 코드, 로그인 시도 횟수, OAuth state, refresh 토큰 · 세션 |
| MinIO | 업로드한 프로필 이미지 |

- 탈퇴 회원은 30일 보존 후 스케줄러가 파기한다. **보고 파기 요청이 모두 완료된 회원만** 지운다. 고아 프로필 이미지는 정리 스케줄러가 지운다.
- 테이블 · 컬럼 정본은 [entity-design.md §1](entity-design.md#1-auth--회원).
- enum 컬럼은 `@Enumerated(STRING)` 에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 를 함께 건다. 빠지면 Hibernate 6 이 MySQL 네이티브 `enum(...)` 을 만들어 값을 늘릴 때마다 ALTER 가 필요하고, dev `ddl-auto: update` 는 기존 컬럼을 고치지 않아 새 값 INSERT 가 실패한다.
- auth 엔티티는 `Persistable<Long>` 을 구현하고 `isNew()` = `createdAt == null` 이다. Snowflake 로 ID 를 미리 정해 기본 판정이 merge 로 가면 INSERT 전 SELECT 가 나가고 겹치는 ID 를 조용히 덮어쓰기 때문이다. 그래서 **기존 행을 고칠 때는 엔티티를 조회해 변경 감지로 바꾼다** — 도메인에서 새로 매핑한 엔티티를 `save` 하면 PK 위반이다.

**가입 흐름** (`auth` 컨텍스트가 받고, 회원 · 동의 저장은 `member` 의 포트 · `MemberConsentProcessor` 를 쓴다)

- 인증 코드: 숫자 6자리(시안 Signup-code). 한도 · 수명은 `auth.email-send.*`(env `AUTH_EMAIL_SEND_*`, 기본 코드 5분 · 재발송 쿨다운 60초 · IP당 발송 10회/1시간 · 오입력 5회 · 인증 완료 30분 · IP당 검증 30회/1시간). IP 발송 상한은 쿨다운을 통과해 **실제로 발송한 요청만** 센다. IP 상한 둘은 저장소 장애에 fail-open. 검증은 앞뒤 공백을 무시한다.
- **가입 여부가 응답으로 새지 않는다**(계정 열거 방지). 이미 가입된 이메일에도 메일로 보내지 않는 무작위 미끼 코드를 같은 수명으로 저장하고 실패 횟수를 초기화한다 — 발송 · 검증의 모든 응답(AUTH_003 · 004 · 005 포함)이 미가입과 같다. 가입된 이메일에는 코드 대신 "이미 가입된 계정" 안내 메일만 간다. 미끼를 맞혀 인증 표시가 생겨도 가입은 `MEMBER_001` 로 막힌다. 탈퇴 회원도 행이 파기될 때까지 이메일을 점유한다.
- Redis 키는 `{prefix}:auth:{emailVerificationCode|emailVerificationFail|emailVerificationCooldown|emailVerified|emailSendIp|emailVerifyIp}:{정규화한 이메일 또는 IP}`. 이메일은 trim + 소문자(`EmailNormalizer`)로 키와 저장값을 맞춘다. Redis 장애는 503 `AUTH_006` 이다.
- IP 상한의 키는 `X-Real-IP` 다 (nginx 가 덮어쓰고 게이트웨이가 그대로 넘긴다). `X-Forwarded-For` 는 앞쪽 값을 클라이언트가 바꿀 수 있고 마지막 값은 게이트웨이가 덧붙인 nginx 주소라 쓰지 않는다.
- 메일은 `authMailTaskExecutor` 에서 비동기로 보내고(SMTP 접속 · 응답 · 쓰기 timeout 5초), 실패는 로그만 남긴다. 큐가 차면 그 메일을 로그만 남기고 버린다(요청은 성공). Boot 기본 `applicationTaskExecutor` 는 `AuthServiceAsyncConfig` 가 같은 이름으로 따로 두고 한정자 없는 `@Async` 기본값에 연결한다. health 는 SMTP 를 보지 않는다(`management.health.mail.enabled: false`).
- 가입: 인증 완료 확인(Redis) → 비밀번호 BCrypt 해시(트랜잭션 밖) → 회원 · 동의 저장(`GeneralSignupProcessor` 트랜잭션) → 커밋 뒤 인증 표시 소비. 필수 동의는 이용약관 · 개인정보 · 만 19세 이상, **건강정보 동의는 별도 필드의 선택 항목**이고 동의했을 때만 행을 남긴다. 문서 버전은 `legal.*-version` 설정값. 동시 가입 중복은 `uk_member_email` 이 막고 `MEMBER_001`(409)로 바뀐다. **가입 응답에는 토큰이 없다** — 이어서 로그인한다.
- 입력 규칙은 시안 Signup-account 와 같다 — 비밀번호 8~20자 · 영문자와 숫자 필수 · 공백 금지 · 특수문자는 선택(상한 20자는 BCrypt 72바이트 한도 안에 두려는 값), 닉네임 2~10자(앞뒤 공백을 지우고 저장).

**화면 계약** (2026-10-01 결정, 프론트 S13-2~4 · S02-2~4)

- 이메일 단계(S13-2)에 **"이미 가입된 이메일" 상태를 두지 않는다.** send-code 는 가입 여부와 무관하게 같은 응답이라, 화면은 늘 코드 단계(S13-3)로 넘어가고 "이미 가입한 이메일이면 코드 대신 안내 메일이 가요" 같은 중립 문구를 함께 보여 준다.
- **가입 요청(`POST /api/v1/auth/signup`)은 동의 단계 뒤에 보낸다.** 개인정보 수집 동의가 수집보다 먼저여야 해서다. 계정 입력(S13-4)은 화면이 들고 있다가 S02-2 성인 확인 · S02-3 가입 동의를 마친 뒤(S02-3 `가입하기`) 동의 값과 함께 한 번에 보낸다. 가입 응답에 토큰이 없으므로 화면은 이어서 로그인(#57)하고 동네를 저장(#60)한다.
- S02-4 증상 보고 동의는 가입 뒤라 건강정보 동의 API(#59)로 따로 보낸다 — 홈의 동의 시트와 같은 경로다. 가입 요청의 `sensitiveHealthInfoAgreed` 는 비워 두면(false) 된다. S02-3 의 `[선택] 주간 보고 알림` 은 가입 API 에 필드가 없다(푸시 구독 때 따로 받는다).
- verify-code 는 토큰을 주지 않는다. 인증 완료 표시는 서버(Redis)에 남고 기본 30분이 지나면 사라지므로, 그 뒤 가입 요청은 `AUTH_007` 이 되고 화면은 이메일 단계부터 다시 한다.
- 발송 제한(`AUTH_001` 쿨다운 60초 · `AUTH_002` IP 상한 1시간 창)은 남은 시간을 응답에 싣지 않는다. 화면 문구는 "잠시 뒤 다시 시도해 주세요" 처럼 시간을 못 박지 않는다.

**설정 · 기동 규칙**

- 프로필은 dev / prod 만(로컬 없음), 값은 환경변수. prod 는 Swagger(springdoc) 를 끈다.
- 게이트웨이와 반드시 같은 값: `JWT_ACCESS_KEY`, `REDIS_KEY_PREFIX`. 게이트웨이의 `AUTH_SERVICE_APP_NAME` 은 이 서비스의 `SPRING_APPLICATION_NAME` 과 같다.
- 기동 시 JWT 설정 검사 — 키 길이(access · refresh 키 UTF-8 64바이트 이상, null · 공백 금지)는 security-core `JwtAuthProperties` 가 바인딩 시점에, 만료 정책(access 15분 이하, 0 이하 금지)은 auth 의 `JwtAuthPropertiesValidator` 가 검사한다. 어느 쪽이든 어기면 기동 실패 (짧은 키는 모든 토큰을 조용히 401 로 만든다).
- access token 블랙리스트 키는 게이트웨이와 같은 `{prefix}:auth:accessTokenBlacklist:{jti}`, TTL 은 토큰 남은 만료 시간. Redis 장애는 항상 503 `SECURITY_008` (fail-closed).
- SMTP 계정 `MAIL_USERNAME` · `MAIL_PASSWORD` 는 기본값이 없다(auth 전용 필수 키). 동의 문서 버전 `legal.*-version` 은 비거나 20자를 넘으면 기동 실패(`LegalDocumentProperties`).
- persistence-core 의 Snowflake · QueryDSL · JPA Auditing 을 `AuthServiceBeansConfig` 에서 켠다. Snowflake 는 기본 datacenter 0 / worker 0 — 인스턴스를 늘리면 `SNOWFLAKE_WORKER_ID` 를 인스턴스마다 다르게 준다.

## service/surveillance-service

| 컨텍스트 | 책임 |
|----------|------|
| `district` | 행정동 마스터 조회 (공개 API + 코드 검증 내부 API) |
| `report` | 주간 건강 보고 upsert |
| `aggregate` | 행정동 × 주 집계, 자료 부족 판정, 검토 후보 신호 |
| `advisory` | 운영자 검토, AI 초안, 승인·수정·발행 이력, 공개 안내 |
| `official` | 질병관리청 감시 자료 조회 (자가보고와 분리) |

- 스키마: MySQL `surveillance` (물리 DB 이름 `sneezecast_surveillance`), **auth 와 다른 DB 계정**.
- **`member_id` 를 저장하지 않는다.** 보고자는 가명 키(`reporter_key`)로만 식별한다. 예외는 운영자 감사 컬럼(`operator_id`)뿐이다.
- 테이블 · 컬럼 정본은 [entity-design.md §2 · §3](entity-design.md#2-surveillance--자가보고와-안내).

**설정 · 기동 규칙**

- 프로필은 dev / prod 만, 값은 환경변수. prod 는 Swagger 를 끈다. 보안은 security-core `resourceserver`(검증만, 발급 없음).
- Redis · MinIO 를 쓰지 않는다. 폐기 토큰 차단은 게이트웨이 블랙리스트에 맡기므로 **서비스 포트를 외부에 노출하지 않는다.**
- `JWT_ACCESS_KEY` 는 auth · 게이트웨이와 같은 값 (UTF-8 64바이트 미만 · 공백이면 security-core `JwtResourceServerProperties` 가 기동을 막는다), `SPRING_APPLICATION_NAME` 은 게이트웨이 `SURVEILLANCE_SERVICE_APP_NAME` 과 같은 값.
- `REPORTER_KEY_PEPPER`(`surveillance.reporter-key.pepper`) — 32자 미만이면 기동 실패. 기동 로그에는 SHA-256 앞 8자 지문만 남긴다. Vault 의 surveillance 경로에만 두고 auth 에는 주지 않는다.

## service/batch-service

- SGIS 행정동 마스터 적재 (연 1회 수동), 질병관리청 전수신고 API 주간 적재, 표본감시(감염병포털 화면 데이터) 주간 적재. 잡 · 주기 · Quartz 규칙은 [entity-design.md §4](entity-design.md#4-batch-service--적재-잡과-스케줄).
- **상주 서비스 안의 Quartz 스케줄러**가 Spring Batch 잡을 실행한다 (hondigagae batch-service 와 같은 구성). 잡 스토어는 in-memory — JDBC 스토어를 쓰면 surveillance 스키마에 `QRTZ_*` 테이블만 는다. 주기 트리거는 코드(`QuartzScheduleConfig`)가 들고, 스레드는 1개.
- 기동 시 잡 자동 실행은 끈다 (`spring.batch.job.enabled: false`). 수동 실행은 잡 이름을 지정해서만 한다.
- Spring Batch 메타 테이블(`BATCH_*`)은 **surveillance 스키마**(`sneezecast_surveillance`)에 둔다 (hondigagae 와 같이 적재 대상 스키마에, 데이터소스 하나). 접속 계정은 surveillance-service 와 다른 적재용 계정이다. 메타 테이블은 dev 에서 애플리케이션이 만들고, prod 는 DB 담당자가 직접 적용한다. 기본값은 `never` 로 둔다 — 새 프로필이 조용히 DDL 을 도는 쪽이 더 위험하다.
- surveillance 스키마의 `district` · `official_surveillance` · `official_source_snapshot` 에 쓰기만 한다 (메타 테이블은 위). 적재는 멱등 upsert. **서비스별 스키마 원칙의 유일한 예외**다 (hondigagae batch → tour 스키마와 같은 관계). 대상 테이블 구조는 surveillance 가 정본이다 ([entity-design.md §3](entity-design.md#3-surveillance--외부-원천-적재)).
- **JPA 를 쓰지 않는다** — 쓰기는 JDBC. 엔티티가 없으니 `ddl-auto` 도 없고, batch 가 돌리는 DDL 은 dev 의 `BATCH_*` 생성뿐이다. 대상 테이블에 엔티티를 두면 구조가 surveillance 와 둘로 갈라진다.
- 주기 실행 스위치 `BATCH_SCHEDULE_ENABLED`(`batch.schedule.enabled`) — 정확히 `true` 이고 `spring.batch.job.enabled` 가 false 일 때만 스케줄러가 시작된다. 기본 dev 켜짐 · prod 꺼짐 (첫 적재 잡을 dev 에서 관찰한 뒤 켠다). 잡 트리거는 반드시 `QuartzScheduleConfig` 안에 `@Bean` 으로 둔다 — 밖에 두면 스위치가 닿지 않는다.
- **실행 브리지** (`domainlayer/schedule`): 트리거 → `SpringBatchLaunchQuartzJob`(잡 공용, JobDataMap 의 `jobName` · `blockedBy`) → `ScheduledJobLaunchFacade` → 겹침 가드 → `JobLauncher.run`. 예정 발화 시각을 `batch.schedule.time-zone`(기본 Asia/Seoul) 초 단위로 바꾼 `runAt` 만 identifying 이고 `trigger=quartz` 는 기록용이다 (실제 발화 시각은 몇 ms 이를 수 있어 `runAt` 에 쓰지 않는다). `SCHEDULE_001`(JOB_NOT_FOUND) · `002`(LAUNCH_FAILED)는 그 발화만 실패하고 즉시 재시도 없이 다음 주기에 다시 돈다. `003`(DUPLICATE_JOB_NAME)은 기동 실패다.
- **겹침 판정**: 같은 잡의 중복 발화는 `@DisallowConcurrentExecution`, 다른 잡 · 수동 실행 JVM(`--spring.batch.job.name`)과의 겹침은 `BATCH_JOB_EXECUTION` 의 STARTING · STARTED · STOPPING 실행으로 본다 — 겹침 목록(자기 자신 + `blockedBy`) 중 하나라도 돌고 있으면 그 주기를 건너뛴다. 시작한 지 `batch.schedule.stale-running-after`(기본 6h)를 넘긴 실행은 죽은 JVM 의 잔재로 보고 ERROR 로그만 남긴 채 무시한다. 겹침 판정은 조회와 실행 사이에 틈이 있는 best-effort 다 — 같은 `runAt` 이 아니면 Spring Batch 도 막지 않는다. 단일 인스턴스 + 사람의 수동 실행이라 받아들인다.
- **주간 잡을 붙이는 법**: `BatchScheduleProperties` 에 cron 필드(기본값 포함)를 더하고, `QuartzScheduleConfig` 에 `newJobDetail(잡 이름, 겹칠 잡...)` · `newCronTrigger(...)` 로 만든 `JobDetail` · `Trigger` 빈 두 개만 더한다. 지금은 스케줄할 잡이 없어 스케줄러가 빈 채로 돈다.
- **지표**: `batch.schedule.fire`(Counter, tag `job` · `result`=`launched`/`skipped_running`/`failed`), `batch.schedule.last.fire.timestamp`(Gauge, tag `job`, epoch 초 — 결과와 무관하게 "스케줄러가 발화는 하고 있나"). 이름 · 태그는 hondigagae 와 같다.
- `districtImportJob`(SGIS → `district`)은 **수동 실행 전용**이다 (Quartz 트리거 없음): `--spring.batch.job.enabled=true --spring.batch.job.name=districtImportJob --spring.main.web-application-type=none year=2025 runAt=<ISO>`. SGIS 호출은 `RestClient` + connect / read timeout 이고, 연 1회 · 실행당 약 36회라 WebFlux · 서킷브레이커를 두지 않는다. 인증키 `SGIS_CONSUMER_KEY` · `SGIS_CONSUMER_SECRET` 이 비어도 기동은 성공하고 잡 실행 때 `SGIS_CREDENTIALS_MISSING` 으로 실패한다. 프로세스 종료 코드는 잡 결과와 무관하다 — 결과는 로그와 `BATCH_JOB_EXECUTION.STATUS` 로 확인한다 (hondigagae 와 같다).
- 트랜잭션 매니저는 둘이다 — `@Primary transactionManager`(DataSource, JobRepository · 적재 쓰기 구간)와 tasklet 스텝용 `taskletTransactionManager`(무자원). 외부 호출을 품은 tasklet 이 스텝 트랜잭션으로 커넥션을 쥐지 않게 한다. 무자원 매니저도 트랜잭션 동기화를 켜서 감싸지 않은 `JdbcTemplate` 호출은 커넥션을 스텝 끝까지 스레드에 묶으므로, **Facade 의 DB 접근은 전부 primary 매니저의 `TransactionTemplate` 안에서 한다** — 원천 호출 전 읽기는 readOnly 트랜잭션(즉시 반납), 판정 · 쓰기는 원천 호출 뒤 한 쓰기 트랜잭션.
- 상주 프로세스라 웹 서버(starter-web)를 둔다 — Quartz 스레드는 데몬이라 JVM 을 붙들지 못한다. 컨트롤러는 없고 springdoc 은 끈다. 공개 API 가 없어 security-core 를 쓰지 않으므로 actuator 가 인증 없이 열린다 — **포트를 외부에 노출하지 않는다.**
