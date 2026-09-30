# 백엔드 모듈 구조

모듈 구성과 각 모듈에 **무엇을 넣고 무엇을 넣지 않는지**의 정본이다. 계층·호출 규칙은 [architecture-guide.md](architecture-guide.md).

## 전체 구조

```text
backend/
├── core/            라이브러리 (jar, bootJar off)
│   ├── common-core          응답 봉투, 검증 오류 변환, Swagger·Jasypt 공통
│   ├── persistence-core     JPA Auditing, QueryDSL, Snowflake ID
│   ├── redis-core           Redis Sentinel 설정
│   └── security-core        JWT 검증, 역할·scope 해석, 인증 오류 응답
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
| `storage-core` (MinIO) | 이미지·파일 기능이 없다. 이용자 업로드는 개인정보 원칙상 받지 않는다 | 공식 자료 원본 보관·안내 첨부 이미지가 실제로 필요해질 때 |
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
- 사용처는 현재 auth-service(세션·OAuth state)뿐이다.

## core/security-core

- 서비스 측 JWT 검증 (Resource Server), `SecurityFilterChain` 기본 구성
- 역할 `USER` / `OPERATOR`, scope claim 해석 (`report:write` — 민감정보 동의를 마친 회원에게만 발급)
- 인증·인가 실패를 `Response` 봉투로 쓰는 오류 writer

**넣지 않는 것**: 토큰 발급(auth-service 소유), 회원 조회.

---

## cloud/service-discovery

Eureka 서버. 서비스는 `@EnableDiscoveryClient` 로 등록하고, 게이트웨이·Feign 은 `lb://<서비스명>` 으로 찾는다. batch-service 도 등록한다.

## cloud/api-gateway

- 라우팅, CORS, JWT 1차 검증. 토큰이 없는 요청은 통과시키고 권한 판단은 각 서비스가 한다.
- **`/internal/**` 은 라우팅하지 않는다.** 라우트 커버리지 테스트로 막는다.
- 오류 응답도 `Response` 봉투를 쓴다.

---

## service/auth-service

| 컨텍스트 | 책임 |
|----------|------|
| `auth` | 카카오 소셜 로그인, 이메일 가입·로그인, 토큰 발급·재발급·폐기 |
| `member` | 회원 (성명·프로필 이미지 없음), 탈퇴 |
| `consent` | 민감정보 처리·알림 수신 동의와 철회 이력 |
| `region` | 회원이 선택한 행정동 |
| `notification` | PWA 푸시 구독, 안내 발행 시 팬아웃 발송, 발송 로그 |

- 스키마: MySQL `auth`. Redis 사용.
- **증상 보고를 저장하지 않는다.**

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
- surveillance 스키마의 `district` · `official_surveillance` 에 쓰기만 한다. 적재는 멱등 upsert.
