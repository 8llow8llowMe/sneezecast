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
- `resourceserver` 패키지 — 서비스 측 JWT 검증(Resource Server), `SecurityFilterChain` 기본 구성. claim 이 규약과 다른 토큰은 401 `TOKEN_INVALID` 로 거부한다 (auth 쪽과 같은 판정)
- 역할 `USER`(일반 회원) / `OPERATOR`(검토·안내 발행) / `ADMIN`(관리자 페이지 — 회원·역할 부여, 운영 설정, 참조 데이터 수동 적재. 운영 API 도 허용), scope claim 해석 (`report:write` — 민감정보 동의를 마친 회원에게만 발급)
  - authority 는 역할 이름 그대로(`ROLE_` 접두어 없음) + scope 마다 `SCOPE_<scope>`. 검사는 `hasAuthority('OPERATOR')`, `hasAuthority(SecurityScope.REPORT_WRITE_AUTHORITY)` 로 한다 — `hasRole(...)` 은 동작하지 않는다.
  - scope 문자열·claim 이름은 `SecurityScope` 한 곳에만 둔다.
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
| `consent` | 민감정보 처리·알림 수신 동의와 철회 이력 |
| `region` | 회원이 선택한 행정동 |
| `notification` | PWA 푸시 구독, 안내 발행 시 팬아웃 발송, 발송 로그 |

- 스키마: MySQL `auth`. Redis · MinIO 사용.
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
| MySQL `member_consent` | 동의 종류 · 동의/철회 이력 |
| Redis (TTL) | 이메일 인증 코드, 비밀번호 재설정 코드, 로그인 시도 횟수, OAuth state, refresh 토큰 · 세션 |
| MinIO | 업로드한 프로필 이미지 |

- 탈퇴 회원은 30일 보존 후 스케줄러가 파기한다. 고아 프로필 이미지는 정리 스케줄러가 지운다.

**설정 · 기동 규칙**

- 프로필은 dev / prod 만(로컬 없음), 값은 환경변수. prod 는 Swagger(springdoc) 를 끈다.
- 게이트웨이와 반드시 같은 값: `JWT_ACCESS_KEY`, `REDIS_KEY_PREFIX`. 게이트웨이의 `AUTH_SERVICE_APP_NAME` 은 이 서비스의 `SPRING_APPLICATION_NAME` 과 같다.
- 기동 시 JWT 설정을 검사한다 — access 만료 15분 초과, HS512 키 64바이트 미만이면 기동 실패 (짧은 키는 모든 토큰을 조용히 401 로 만든다).
- access token 블랙리스트 키는 게이트웨이와 같은 `{prefix}:auth:accessTokenBlacklist:{jti}`, TTL 은 토큰 남은 만료 시간. Redis 장애는 항상 503 `SECURITY_008` (fail-closed).

## service/surveillance-service

| 컨텍스트 | 책임 |
|----------|------|
| `district` | 행정동 마스터 조회 (공개 API + 코드 검증 내부 API) |
| `report` | 주간 건강 보고 upsert |
| `aggregate` | 행정동 × 주 집계, 자료 부족 판정, 검토 후보 신호 |
| `advisory` | 운영자 검토, AI 초안, 승인·수정·발행 이력, 공개 안내 |
| `official` | 질병관리청 감시 자료 조회 (자가보고와 분리) |

- 스키마: MySQL `surveillance`, **auth 와 다른 DB 계정**.
- **`member_id` 를 저장하지 않는다.** 보고자는 가명 키(`reporter_key`)로만 식별한다. 예외는 운영자 감사 컬럼(`operator_id`)뿐이다.

## service/batch-service

- SGIS 행정동 마스터·경계 적재 (연 1회 + 수동), 질병관리청 감시 자료 주간 적재.
- **상주 서비스 안의 Quartz 스케줄러**가 Spring Batch 잡을 실행한다 (hondigagae batch-service 와 같은 구성). 잡 스토어는 in-memory — JDBC 스토어를 쓰면 surveillance 스키마에 `QRTZ_*` 테이블만 는다. 주기 트리거는 코드(`QuartzScheduleConfig`)가 들고, 스레드는 1개.
- 기동 시 잡 자동 실행은 끈다 (`spring.batch.job.enabled: false`). 수동 실행은 잡 이름을 지정해서만 한다.
- Spring Batch 메타 테이블은 dev 에서 애플리케이션이 만들고, prod 는 DB 담당자가 직접 적용한다. 기본값은 `never` 로 둔다 — 새 프로필이 조용히 DDL 을 도는 쪽이 더 위험하다.
- surveillance 스키마의 `district` · `official_surveillance` 에 쓰기만 한다. 적재는 멱등 upsert. **서비스별 스키마 원칙의 유일한 예외**다 (hondigagae batch → tour 스키마와 같은 관계). 대상 테이블 구조는 surveillance 가 정본이다.
