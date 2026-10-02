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
- 인증 주체 `MemberLoginActive` — 회원 ID · 역할 · scope · access jti(블랙리스트 키) · 만료 시각(`expiresAt`, 블랙리스트 TTL 계산용) · 세션 ID(`sid` claim, 없으면 null). refresh 토큰은 `sid` 가 필수이고 jti 는 발급마다 새로 만든다(같은 jti 를 다시 쓰면 iat 가 초 단위라 같은 초에 똑같은 토큰이 나와 회전이 무력화된다).
- 발급은 `JwtAuthProvider.IssuedToken(value, tokenId, expiresAt)` 을 돌려준다 — 세션에 access jti · 만료를, 회전에 새 refresh jti 를 넘기기 위해서다. claim 이름(`role` · `sid`)은 `JwtClaimNames`, `scope` 는 `SecurityScope` 한 곳에 둔다.
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
- **JWT 거부**(`SECURITY_00x`)와 **게이트웨이 자체 오류**(`GATEWAY_00x` — 경로 거부 400 · 라우트 없음 404 · 인스턴스 없음 · 업스트림 연결 실패 503 · 업스트림 응답 타임아웃 504)는 `Response` 봉투를 쓴다. 그 밖의 오류는 Spring 기본 형식이다. `SECURITY_00x` 는 security-core `SecurityErrorCode` 와 같은 문자열이다 (계약 테스트로 고정).
- **경로 우회 거부**: 원문 경로에 `..` · `;` · `\` · `%2e` · `%2f` · `%5c` · `%25` · `%3b` · `%00` 이 있으면 라우트 매칭 전에 400 `GATEWAY_001` 로 막는다 (`PathTraversalRejectWebFilter`) — 업스트림 정규화로 `/internal/**` 에 닿는 우회를 막는다. 리터럴 `\` 는 그보다 앞의 URI 해석 단계에서 봉투 없는 400 이 된다.
- **신뢰 프록시 헤더 규칙**: 접속 주소가 `GATEWAY_TRUSTED_PROXIES`(nginx IP · CIDR, 비거나 호스트명이면 기동 실패)가 아니면 `X-Forwarded-*` · `Forwarded` · `X-Real-IP` 를 지우고 `X-Real-IP` 를 접속 주소로 덮어쓴다 (`TrustedProxyHeaderWebFilter`). 접근 로그 `clientIp` 도 `X-Real-IP` → 접속 주소 순이고 `X-Forwarded-For` 는 쓰지 않는다.
- CORS 허용 오리진은 security-core `AuthSecurityConfigurer` 와 같은 목록이다 (`CorsOriginContractTest` 가 소스를 대조한다).
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

**그 밖의 설정**: 응답 CORS 헤더 중복 제거(`DedupeResponseHeader`, auth-service 도 CORS 를 붙이므로), 업스트림 connect 2초 · response 10초 (연결 실패 503 · 응답 초과 504, 둘 다 `GATEWAY_00x` 봉투).

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
| 비밀번호 | `POST /password/reset/send-code` · `POST /password/reset/verify-code` (일회용 재설정 토큰) · `POST /password/reset` · `POST /me/password` (변경) · `POST /me/password/setup` (소셜 가입자 최초 설정) |
| 세션 | `GET /sessions` · `DELETE /sessions/{sessionId}` · `DELETE /sessions` (현재 기기를 뺀 전부) |
| 내 정보 | `GET /me` · `PATCH /me` · `POST /me/withdraw` (#59) · 프로필 이미지 업로드 · `DELETE /me/profile-image` (#112) |

**저장소**

| 위치 | 대상 |
|------|------|
| MySQL `member` | 이메일(unique) · 비밀번호 해시 · 닉네임 · 프로필 이미지(소셜 URL / 업로드 오브젝트 키) · 역할 · 소셜 제공자 · 상태 · 탈퇴 시각 |
| MySQL `member_consent` | 동의 종류 · 문서 버전 · 동의/철회 이력 |
| MySQL `member_region` | 선택한 행정동 (회원당 1개) |
| MySQL `report_purge_request` | 탈퇴 · 건강정보 동의 철회 시 surveillance 원시 보고 파기 요청 (완료될 때까지 재시도) |
| Redis (TTL) | 이메일 인증 코드, 비밀번호 재설정 코드 · 재설정 토큰(해시), 로그인 · 비밀번호 확인 시도 횟수, OAuth state, refresh 세션 |
| MinIO | 업로드한 프로필 이미지 |

- 탈퇴 회원은 30일 보존 후 스케줄러가 파기한다. **보고 파기 요청이 모두 완료된 회원만** 지운다. 고아 프로필 이미지는 정리 스케줄러가 지운다.
- 테이블 · 컬럼 정본은 [entity-design.md §1](entity-design.md#1-auth--회원).
- enum 컬럼은 `@Enumerated(STRING)` 에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 를 함께 건다. 빠지면 Hibernate 6 이 MySQL 네이티브 `enum(...)` 을 만들어 값을 늘릴 때마다 ALTER 가 필요하고, dev `ddl-auto: update` 는 기존 컬럼을 고치지 않아 새 값 INSERT 가 실패한다.
- auth 엔티티는 `Persistable<Long>` 을 구현하고 `isNew()` = `createdAt == null` 이다. Snowflake 로 ID 를 미리 정해 기본 판정이 merge 로 가면 INSERT 전 SELECT 가 나가고 겹치는 ID 를 조용히 덮어쓰기 때문이다. 그래서 **기존 행을 고칠 때는 엔티티를 조회해 변경 감지로 바꾼다** — 도메인에서 새로 매핑한 엔티티를 `save` 하면 PK 위반이다.

**가입 흐름** (`auth` 컨텍스트가 받고, 회원 · 동의 저장은 `member` 의 포트 · `MemberConsentProcessor` 를 쓴다)

- 인증 코드: 숫자 6자리(시안 Signup-code). 한도 · 수명은 `auth.email-send.*`(env `AUTH_EMAIL_SEND_*`, 기본 코드 5분 · 재발송 쿨다운 60초 · IP당 발송 10회/1시간 · 오입력 5회 · 인증 완료 30분 · IP당 검증 30회/1시간). IP 발송 상한은 쿨다운을 통과해 **실제로 발송한 요청만** 센다. IP 상한 둘은 저장소 장애에 fail-open. 검증은 앞뒤 공백을 무시한다.
- **가입 여부가 응답으로 새지 않는다**(계정 열거 방지). 이미 가입된 이메일에도 메일로 보내지 않는 무작위 미끼 코드를 같은 수명으로 저장하고 실패 횟수를 초기화한다 — 발송 · 검증의 모든 응답(AUTH_003 · 004 · 005 포함)이 미가입과 같다. 가입된 이메일에는 코드 대신 "이미 가입된 계정" 안내 메일만 간다. 미끼를 맞혀 인증 표시가 생겨도 가입은 `MEMBER_001` 로 막힌다. 탈퇴 회원도 행이 파기될 때까지 이메일을 점유한다.
- Redis 키는 `{prefix}:auth:{emailVerificationCode|emailVerificationFail|emailVerificationCooldown|emailVerified|emailSendIp|emailVerifyIp}:{정규화한 이메일 또는 IP}`. 이메일은 trim + 소문자(`EmailNormalizer`)로 키와 저장값을 맞춘다. Redis 장애는 503 `AUTH_006` 이다.
- IP 상한의 키는 `X-Real-IP` 다 (nginx 가 덮어쓰고, nginx 가 아닌 출발지면 게이트웨이가 접속 주소로 덮어쓴다). `X-Forwarded-For` 는 앞쪽 값을 클라이언트가 바꿀 수 있고 마지막 값은 게이트웨이가 덧붙인 nginx 주소라 쓰지 않는다.
- 메일은 `authMailTaskExecutor` 에서 비동기로 보내고(SMTP 접속 · 응답 · 쓰기 timeout 5초), 실패는 로그만 남긴다. 큐가 차면 그 메일을 로그만 남기고 버린다(요청은 성공). Boot 기본 `applicationTaskExecutor` 는 `AuthServiceAsyncConfig` 가 같은 이름으로 따로 두고 한정자 없는 `@Async` 기본값에 연결한다. health 는 SMTP 를 보지 않는다(`management.health.mail.enabled: false`).
- 가입: 인증 완료 확인(Redis) → 비밀번호 BCrypt 해시(트랜잭션 밖) → 회원 · 동의 저장(`GeneralSignupProcessor` 트랜잭션) → 커밋 뒤 인증 표시 소비. 필수 동의는 이용약관 · 개인정보 · 만 19세 이상, **건강정보 동의는 별도 필드의 선택 항목**이고 동의했을 때만 행을 남긴다. 문서 버전은 `legal.*-version` 설정값. 동시 가입 중복은 `uk_member_email` 이 막고 `MEMBER_001`(409)로 바뀐다. **가입 응답에는 토큰이 없다** — 이어서 로그인한다.
- 입력 규칙은 시안 Signup-account 와 같다 — 비밀번호 8~20자 · 영문자와 숫자 필수 · 공백 금지 · 특수문자는 선택(상한 20자는 BCrypt 72바이트 한도 안에 두려는 값), 닉네임은 **앞뒤 공백을 지운 뒤 2~10자**(`@StrippedSize` — 원문 길이로 재면 `" 가 "` 가 통과해 1자로 저장된다)이고 지운 값을 저장한다. 규칙 상수의 정본은 member 도메인의 `MemberInputPolicy` 이고, 가입(auth) · 내 정보 수정(member) 검증이 함께 참조한다.

**로그인 · 토큰 · 세션**

- 로그인 시도 제한은 `auth.login.*`(env `AUTH_LOGIN_*`, 기본 이메일당 5회 실패 → 10분 잠금 · IP당 실패 30회/1시간). 이메일 키는 **계정 존재 여부와 무관하게** 세고 잠근다 — 잠금 응답이 "가입된 이메일" 신호가 되지 않게. 잠금 검사는 회원 조회보다 먼저다. **두 카운터는 BCrypt 비교 전에 먼저 올린다** — 실패한 뒤에 올리면 동시에 들어온 수백 요청이 모두 비교를 거쳐 상한이 무력화된다. 비밀번호가 맞으면(탈퇴 · 정지여도) 이메일 카운터는 지우고 IP 카운터는 자기 몫만 되돌린다. 잠글 때 카운터는 지우지 않는다 — 잠금 직전에 확인을 통과한 동시 요청이 1부터 다시 세어 비교까지 가지 않게. 카운터 TTL 은 첫 실패부터 잠금 시간이라 잠금보다 먼저 사라진다. 두 카운터 모두 저장소 장애에 fail-open.
- 미존재 이메일 · 비밀번호 없는(소셜) 계정 · 비밀번호 불일치는 모두 `AUTH_011` 이고, 미존재 · 비밀번호 없음에도 더미 BCrypt 비교를 돌려 응답 시간을 맞춘다. 탈퇴(`MEMBER_002`) · 정지(`MEMBER_003`)는 **비밀번호가 맞을 때만** 드러낸다.
- 세션은 로그인 한 번 = 기기 하나다. 세션 ID 는 로그인 때 만들고 **회전해도 유지**하며, refresh JWT 의 jti 만 매번 바뀐다. JWT 의 `sid` claim 이 세션 ID 다(access · refresh 둘 다).
- Redis 키: `{prefix}:auth:refreshSession:{memberId}:{sessionId}`(HASH — 현재 · 직전 refresh jti, 회전 시각, 기기 이름, 생성 · 마지막 사용 시각, 최근 access jti · 만료) · `{prefix}:auth:refreshSessions:{memberId}`(ZSET, score = 마지막 사용). TTL 은 refresh 만료(기본 14일, 회전마다 갱신). **refresh 토큰 원문은 저장하지 않는다.** 회원당 기기 상한(`auth.session.max-devices`, 기본 5)을 넘으면 가장 오래 안 쓴 세션부터 밀어낸다.
- 재발급 회전은 Lua 하나로 원자 처리한다 — 제시한 jti 가 현재 jti 면 회전, **직전 jti 이고 회전 직후(`auth.session.rotation-grace`, 기본 10초)면 여러 탭의 동시 재발급으로 보고 세션을 두고 `AUTH_016`(409)**, 그 밖의 옛 jti 는 **재사용(탈취 의심)으로 보고 그 세션을 폐기**하고 `AUTH_015`. 세션이 없거나 refresh 가 만료면 `AUTH_014`.
- scope 와 재동의 표시는 로그인 · 재발급마다 동의 이력에서 다시 계산한다. `pendingConsents` = 필수 항목(이용약관 · 개인정보 · 만 19세) 중 현재 문서 버전의 유효 동의가 없는 것(문서 개정 → 재동의 유도, 로그인은 막지 않는다). `report:write` 는 **pendingConsents 가 비어 있고 건강정보 동의가 유효할 때만** 싣는다. "미완료 보고 파기 요청이 없을 것" 조건은 `report_purge_request` 와 함께 #59 에서 더한다.
- 재발급은 회원 상태도 다시 본다. ACTIVE 가 아니면 그 회원의 전 세션을 지운다.
- 로그아웃은 현재 세션(access `sid`)을 지우고 access jti 를 남은 만료 시간만큼 블랙리스트에 올린다. 세션 폐기(`DELETE /sessions/{id}` · `DELETE /sessions`) · 재사용 감지 · 기기 상한 밀어내기는 그 세션에 저장된 **최근 access jti** 를 블랙리스트에 올려 그 기기를 끊는다. 같은 세션에서 그보다 먼저 발급돼 아직 만료되지 않은 access(여러 탭)는 최대 access TTL(15분) 동안 남는다 — 동의 철회와 같은 허용 범위다([architecture-guide.md](architecture-guide.md)). 로그아웃만 Redis 장애를 관용한다(로그를 남기고 200 + 쿠키 삭제 — 쿠키가 지워지면 그 브라우저의 refresh 는 사라진다).
- 기기 이름은 `User-Agent` 를 "OS · 브라우저"(예: `iPhone · Safari`)로 줄여 저장한다. **UA 원문과 IP 는 저장하지 않는다.**
- refresh 토큰은 응답 바디가 아니라 쿠키 `refreshToken`(HttpOnly · Secure · SameSite=Strict · Path=`/api/v1/auth`)으로만 오간다. 웹과 API 는 같은 사이트(`*.sneezecast.com`)라 Strict 로 충분하다. 게이트웨이 · auth 의 CORS 는 `allowCredentials=true` 다.
- 오류 코드: `AUTH_011` 로그인 실패(401) · `012` 이메일 잠금(429) · `013` IP 상한(429) · `014` refresh 없음 · 만료 · 세션 없음(401, 재로그인) · `015` refresh 위조 · 재사용(401) · `016` 동시 재발급 경합(409, 한 번 재시도) · `017` 세션 저장소 장애(503), 검증 `AUTH_113`(로그인 비밀번호 100자 초과) · `AUTH_114`(sessionId 가 UUID 형식이 아님), `MEMBER_002` 탈퇴 · `MEMBER_003` 정지(403).
- Lua 스크립트는 다른 세션의 해시 키를 스크립트 안에서 조립한다(밀어내기 · 전체 삭제). Redis 를 Sentinel 로 쓰는 한 문제없지만, Cluster 로 옮기면 회원 단위 해시태그(`{memberId}`)로 키를 묶어야 한다.

**비밀번호 재설정** (`auth` 컨텍스트, 인증 불필요)

- 인증 코드 발급 · 확인은 가입과 같은 처리기(`EmailCodeProcessor`)를 용도(`EmailCodePurpose` SIGNUP / PASSWORD_RESET)만 바꿔 쓴다. 한도 · 수명도 `auth.email-send.*` 를 그대로 쓴다. 브루트포스 방어를 두 벌로 두면 한쪽만 고쳐져 조용히 어긋나기 때문이다. Redis 키만 따로다: `passwordResetCode` · `passwordResetFail` · `passwordResetCooldown` · `passwordResetSendIp` · `passwordResetVerifyIp`.
- send-code 응답은 **가입 여부와 무관하게 같다**. 메일만 갈린다: ACTIVE 회원(소셜 포함)에게는 재설정 코드, 가입되지 않은 이메일에는 미끼 코드를 저장하고 "가입된 계정 없음" 안내 메일, 탈퇴 · 정지 회원에게는 미끼 코드만(메일 없음).
- verify-code 의 실패 코드는 가입과 같다(`AUTH_003` · `004` · `005` · `010`). 성공하면 일회용 `resetToken` 을 준다 — SecureRandom 32바이트 base64url. Redis 에는 원문이 아니라 SHA-256 해시를 키로(`passwordResetToken:{sha256hex}` → 이메일, TTL `auth.password-reset.token-ttl` 기본 15분) 둔다. 미끼 코드를 맞혀도 같은 모양으로 토큰을 준다.
- reset `{resetToken, newPassword}`: IP 시도 카운터(`passwordResetIp`, 상한 · 창은 `auth.email-send.verify-ip-*`)를 BCrypt 전에 먼저 올리고 넘으면 `AUTH_019`. 토큰은 Lua GET+DEL 로 원자 소비하고, 없음 · 만료 · 이미 씀 · 그 이메일의 ACTIVE 회원 없음은 모두 `AUTH_018` 이다.
- 성공 순서: 새 비밀번호 BCrypt(트랜잭션 밖) → **모든 기기 세션 폐기 + access 블랙리스트** → 비밀번호 저장(커밋) → **같은 범위 2차 폐기** → 그 이메일의 로그인 잠금 · 실패 카운터 해제. 1차 폐기가 실패하면(503) 비밀번호는 그대로이고, 토큰은 이미 소비돼 인증 코드부터 다시 한다. 2차 폐기는 폐기와 커밋 사이에 옛 비밀번호로 BCrypt 를 통과한 로그인이 남긴 세션을 지우려는 것이고, 실패해도 로그만 남긴다.
- 같은 이메일로 verify-code 를 여러 번 통과하면 토큰이 여러 개 함께 살아 있을 수 있다(각각 1회용 · 15분). 메일함 소유가 전제라 받아들였다.

**내 정보 · 비밀번호 변경 · 설정** (`member` 컨텍스트, 인증 필요)

- `GET /members/me` → `{memberId, email, nickname, provider(EMAIL | KAKAO — DB 값이 null 이면 EMAIL), hasPassword, role, pendingConsents, reportWritable}`. 재동의 · 보고 가능 여부는 토큰과 같은 계산(`MemberConsentProcessor.currentStatus` + auth 의 `ReportScopePolicy` — member 는 `MemberReportScopePort` 로 부른다)이다. 내 동네는 #60, 프로필 이미지는 #112 에서 더한다.
- `PATCH /members/me` 는 닉네임만 바꾼다(가입과 같은 2~10자). 수정은 엔티티를 조회해 변경 감지로 한다 — 리포지토리의 수정 메서드는 `@Transactional(MANDATORY)` 라 트랜잭션 밖에서 부르면 바로 실패한다.
- 비밀번호 변경 `{currentPassword, newPassword}` · 최초 설정 `{newPassword}`: 소셜 계정이 변경을 부르면 `MEMBER_007`, 이미 비밀번호가 있는데 설정을 부르면 `MEMBER_008`. 새 비밀번호 규칙은 가입과 같다(현재와 같아도 막지 않는다).
- 현재 비밀번호 확인은 회원 단위로 횟수를 제한한다(`passwordChangeFail:{memberId}`, 상한 · 잠금은 `auth.login.*` 5회 · 10분). 로그인처럼 **BCrypt 전에 먼저 올리고**, 상한째 틀린 시도부터 `MEMBER_006`(429)이다. 틀리면 `MEMBER_005`.
- 성공 순서: 현재 비밀번호 확인 → 새 비밀번호 BCrypt(트랜잭션 밖) → **지금 기기(access `sid`)를 뺀 다른 기기 세션 폐기 + access 블랙리스트**(sid 가 없으면 전부) → 저장(커밋) → 같은 범위 2차 폐기(실패해도 로그만 — 재설정과 같은 이유). 1차 폐기가 실패하면 저장하지 않고 `MEMBER_009`(503) — member 컨트롤러에는 auth 의 예외 처리기가 걸리지 않아 auth 의 `AUTH_017` 을 member 코드로 바꿔 낸다.
- 비밀번호 최초 설정은 지금은 access 만으로 된다. 탈취된 access(15분 이하)로 이메일 로그인 자격을 만들 수 있다는 위험이 있지만, 소셜(카카오) 회원이 아직 없어(#61) 노출이 없다. 재인증(이메일 코드 · 최근 로그인)을 요구할지는 #61 에서 정한다.
- member 요청 검증은 `MemberRequestExceptionHandler`(member 패키지 전용) 가 `MEMBER_1xx` 로 낸다. auth 검증 오류가 MEMBER 코드로 새지 않게 처리기를 나눴다.
- **컨텍스트 의존 방향**: auth → member 는 자유롭게 쓴다(회원 조회 · 동의 상태 · 재설정의 비밀번호 저장 `MemberCommandProcessor`, 입력 규칙 `MemberInputPolicy`). **member → auth 는 `member/adapter/out/auth` 의 어댑터로만** 잇는다(`MemberReportScopeAdapter` · `MemberSessionRevokeAdapter`) — `member/application` · `member/domain` 은 auth 를 import 하지 않는다. application 계층에 순환이 생기면 컨텍스트를 떼어 내거나 의존 규칙 테스트를 넣을 때 막힌다(hondigagae 와 같은 방향).
- 오류 코드: `AUTH_018` 재설정 인증 만료(400) · `AUTH_019` 재설정 IP 상한(429) · 검증 `AUTH_115` · `116`(resetToken), `MEMBER_004` 회원 없음(404) · `005` 현재 비밀번호 불일치(400) · `006` 확인 잠금(429) · `007` 비밀번호 미설정(409) · `008` 이미 설정됨(409) · `009` 세션 저장소 장애(503) · `100` · `198` · `199` 요청 형식, 검증 `MEMBER_101~107`.

**화면 계약** (2026-10-01 결정, 프론트 S13-2~6 · S02-2~4 · S10)

- 이메일 단계(S13-2)에 **"이미 가입된 이메일" 상태를 두지 않는다.** send-code 는 가입 여부와 무관하게 같은 응답이라, 화면은 늘 코드 단계(S13-3)로 넘어가고 "이미 가입한 이메일이면 코드 대신 안내 메일이 가요" 같은 중립 문구를 함께 보여 준다.
- **가입 요청(`POST /api/v1/auth/signup`)은 동의 단계 뒤에 보낸다.** 개인정보 수집 동의가 수집보다 먼저여야 해서다. 계정 입력(S13-4)은 화면이 들고 있다가 S02-2 성인 확인 · S02-3 가입 동의를 마친 뒤(S02-3 `가입하기`) 동의 값과 함께 한 번에 보낸다. 가입 응답에 토큰이 없으므로 화면은 이어서 로그인(#57)하고 동네를 저장(#60)한다.
- S02-4 증상 보고 동의는 가입 뒤라 건강정보 동의 API(#59)로 따로 보낸다 — 홈의 동의 시트와 같은 경로다. 가입 요청의 `sensitiveHealthInfoAgreed` 는 비워 두면(false) 된다. S02-3 의 `[선택] 주간 보고 알림` 은 가입 API 에 필드가 없다(푸시 구독 때 따로 받는다).
- verify-code 는 토큰을 주지 않는다. 인증 완료 표시는 서버(Redis)에 남고 기본 30분이 지나면 사라지므로, 그 뒤 가입 요청은 `AUTH_007` 이 되고 화면은 이메일 단계부터 다시 한다.
- 발송 제한(`AUTH_001` 쿨다운 60초 · `AUTH_002` IP 상한 1시간 창)은 남은 시간을 응답에 싣지 않는다. 화면 문구는 "잠시 뒤 다시 시도해 주세요" 처럼 시간을 못 박지 않는다.
- 로그인(S13-5): `AUTH_011` → wrong("이메일 또는 비밀번호가 맞지 않아요"), `AUTH_012` → locked(잠금은 기본 10분 고정이라 시안의 "10분 뒤" 를 쓸 수 있다), `AUTH_013`(IP 상한)은 locked 와 같은 모양에 "잠시 뒤" 문구.
- 로그인 · 재발급 응답은 `{memberId, role, accessToken, accessTokenExpiresIn(초), pendingConsents, reportWritable}` 이다. access token 은 메모리에만 두고, 만료 전에 `POST /token/reissue` 로 바꾼다(refresh 는 쿠키라 화면이 다루지 않는다 — `fetch` 에 `credentials: 'include'`). `pendingConsents` 가 있으면 약관 재동의 화면(S02-3 reconsent)으로, `reportWritable` 이 false 면 보고 진입에서 증상 보고 동의 시트를 연다.
- **재발급 요청에는 `Authorization` 헤더를 싣지 않는다.** 게이트웨이와 auth 필터는 경로와 무관하게 헤더가 있으면 access 를 검사하므로, 만료된 access 를 실으면 refresh 가 멀쩡해도 401 `SECURITY_002` 로 끝난다. 공통 HTTP 클라이언트가 Bearer 를 자동으로 붙인다면 재발급 호출만 예외로 뺀다.
- 로그아웃 · 세션 API 는 access 가 필요하다. access 가 만료됐으면 먼저 재발급하고 부른다. 재발급도 `AUTH_014` · `AUTH_015` 면 세션이 이미 끊긴 것이라 화면만 로그아웃 상태로 바꾼다(남은 쿠키는 쓸모가 없고 다음 로그인 때 덮인다).
- 재발급 `AUTH_016`(409)은 여러 탭이 동시에 재발급한 경합이다 — 한 번 다시 부르면 된다(브라우저가 이긴 쪽의 새 쿠키를 보낸다). 탭이 셋 이상이거나 응답이 늦게 오면 한 번의 재시도로 부족하거나 늦게 도착한 옛 토큰이 재사용으로 판정될 수 있으니, **탭 사이 재발급을 Web Locks(`navigator.locks`)로 한 번에 하나만** 돌리기를 권한다. 409 가 연달아 오면 재로그인으로 처리한다. `AUTH_014` · `AUTH_015` 는 재로그인(S13-1 `?reason=expired`).
- 로그인 기기(S10 Settings-devices)는 `GET /sessions` 의 `current` 로 "이 기기" 를 표시하고, "다른 기기에서 모두 로그아웃" 은 `DELETE /sessions` 다.
- FE 로컬(`http://localhost:*`)에서 dev API 를 부르면 교차 사이트라 SameSite=Strict 쿠키가 실리지 않는다 — 로그인은 되지만 재발급은 안 된다(access 만료 15분 뒤 재로그인). 로컬 개발은 목을 기본으로 한다.
- 비밀번호 재설정(S13-6)은 프론트 제안 1 이다: verify-code 가 `{resetToken}` 을 주고, 새 비밀번호는 `{resetToken, newPassword}` 로만 보낸다. 토큰은 메모리에만 둔다. `AUTH_018` 이면 `/password/reset?reason=verification-expired`. 토큰은 1회용이라 토큰당 시도 상한은 두지 않았다(제안과 다른 점). 재설정에 성공하면 **모든 기기가 로그아웃**되므로 화면은 이메일 로그인으로 보낸다.
- 비밀번호 변경 · 설정(S10)에 성공하면 **이 기기는 유지되고 다른 기기는 로그아웃**된다(2026-10-02 결정). 화면에 "다른 기기에서는 로그아웃돼요" 안내가 필요하다. `MEMBER_005` → 현재 비밀번호 틀림, `MEMBER_006` → 잠시 막힘, `MEMBER_007` · `008` 은 `hasPassword` 와 어긋난 호출이라 내 정보를 다시 불러온다.
- 내 정보 `GET /members/me` 의 `provider` 는 `EMAIL` / `KAKAO`, `hasPassword` 로 `비밀번호 변경` / `비밀번호 설정` 을 가른다. 재동의 조건은 로그인 응답과 같은 `pendingConsents` 다.

**설정 · 기동 규칙**

- 프로필은 dev / prod 만(로컬 없음), 값은 환경변수. prod 는 Swagger(springdoc) 를 끈다.
- 게이트웨이와 반드시 같은 값: `JWT_ACCESS_KEY`, `REDIS_KEY_PREFIX`. 게이트웨이의 `AUTH_SERVICE_APP_NAME` 은 이 서비스의 `SPRING_APPLICATION_NAME` 과 같다.
- 기동 시 JWT 설정 검사 — 키 길이(access · refresh 키 UTF-8 64바이트 이상, null · 공백 금지)는 security-core `JwtAuthProperties` 가 바인딩 시점에, 만료 정책(access 15분 이하, 0 이하 금지)은 auth 의 `JwtAuthPropertiesValidator` 가 검사한다. 어느 쪽이든 어기면 기동 실패 (짧은 키는 모든 토큰을 조용히 401 로 만든다).
- access token 블랙리스트 키는 게이트웨이와 같은 `{prefix}:auth:accessTokenBlacklist:{jti}`, TTL 은 토큰 남은 만료 시간. Redis 장애는 항상 503 `SECURITY_008` (fail-closed).
- SMTP 계정 `MAIL_USERNAME` · `MAIL_PASSWORD` 는 기본값이 없다(auth 전용 필수 키). 동의 문서 버전 `legal.*-version` 은 비거나 20자를 넘으면 기동 실패(`LegalDocumentProperties`).
- 재설정 토큰 수명 `auth.password-reset.token-ttl`(env `AUTH_PASSWORD_RESET_TOKEN_TTL`, 기본 PT15M)은 0 이하면 기동 실패다. 필수 키가 아니다.
- persistence-core 의 Snowflake · QueryDSL · JPA Auditing 을 `AuthServiceBeansConfig` 에서 켠다. Snowflake 는 기본 datacenter 0 / worker 0 — 인스턴스를 늘리면 `SNOWFLAKE_WORKER_ID` 를 인스턴스마다 다르게 준다.

## service/surveillance-service

| 컨텍스트 | 책임 |
|----------|------|
| `district` | 행정동 마스터 조회 (읽기 전용, 쓰기는 batch 적재뿐). 공개 `GET /api/v1/districts?query=` — 현행만, 동 이름 · `시도 시군구` 포함 검색, FE 계약상 `dataBody` 는 페이지 없는 배열 최대 20건 · `GET /api/v1/districts/{code}` — 폐지 코드도 `active=false` 로 200. 내부 `GET /internal/v1/districts/{code}` — auth 의 내 동네 코드 검증 |
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
