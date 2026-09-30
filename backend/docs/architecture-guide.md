# 백엔드 아키텍처 가이드

Spring Cloud MSA + Hexagonal. 모듈 목록은 [modules.md](modules.md), 코드 규칙은 [coding-conventions.md](coding-conventions.md).
도메인 불변식(보고·집계·개인정보)의 정본은 루트 [`CLAUDE.md`](../../CLAUDE.md) 이고, 이 문서는 그것을 서비스 경계로 옮긴다.

## 1. 서비스 구성

```text
client ──▶ api-gateway ──lb://──▶ auth-service ─────────┐
                │                                         │ Feign /internal/v1
                └────────lb://──▶ surveillance-service ◀─┘
                                        ▲
             batch-service (Quartz) ──────┘ 참조 데이터 적재 (DB 쓰기)
             service-discovery (Eureka) — 게이트웨이·서비스 등록
```

**서비스를 이렇게 나눈 이유**

- **개인정보 경계가 곧 서비스 경계다.** 신원(auth)과 가명 증상(surveillance)을 다른 프로세스·다른 DB 계정에 둬서, 둘을 결합할 수 있는 주체를 없앤다.
- **보고·집계·검토·발행은 한 서비스다.** 운영자가 검토한 집계값과 발행문에 인용된 값이 같아야 하는데, 서비스를 나누면 이 값을 경계 너머로 복제·동기화해야 한다. `advisory` 는 패키지(컨텍스트) 경계로 분리해 두고, 규모가 커지면 떼어 낸다.
- **적재는 batch-service 로 분리한다.** 외부 API 쿼터·장애가 사용자 요청 경로에 번지지 않게 하고, 주기 실행은 서비스 안의 Quartz 스케줄러가 맡는다.
- **Kafka 는 쓰지 않는다.** 알림 팬아웃은 outbox + Feign 으로 충분하다. outbox 를 두었으므로 발행자만 바꾸면 이행된다.

## 2. 패키지 구조

```text
com.sneezecast
├── domainlayer/<context>
│   ├── adapter
│   │   ├── in/web          *WebController, request/response DTO, exception handler
│   │   ├── in/internal     /internal/v1 — 게이트웨이가 라우팅하지 않는 서비스 간 API
│   │   ├── in/scheduler    집계·outbox 디스패치·보관 기간 삭제
│   │   ├── out/persistence entity, repository, repository/custom (QueryDSL)
│   │   └── out/client      Feign·외부 API (feign/dto, support)
│   ├── application
│   │   ├── command · info · mapper · model · exception
│   │   ├── port/in         *WebUseCase / *InternalUseCase
│   │   ├── port/out        *RepositoryPort / *QueryPort / query/*QueryResult
│   │   └── service         *WebFacade, processor/*Processor, presenter
│   └── domain/model
└── global/config           *BeansConfig, *PropertiesConfig, *SwaggerConfig
```

애플리케이션 클래스는 `@EnableDiscoveryClient` + `@SpringBootApplication(scanBasePackages = {"com.sneezecast.domainlayer", "com.sneezecast.global"})`. core 설정은 `@Import` 로 명시한다. `@EnableFeignClients` 는 필요한 서비스에만.

## 3. 계층 흐름

```text
Controller → WebUseCase → WebFacade → Processor → Port → Adapter
                                  Info → Presenter → Response
```

| 계층 | 책임 | 하지 않는 것 |
|------|------|--------------|
| Controller | 요청 검증, `*WebUseCase` 호출, `ResponseEntity<Response<T>>` 반환 | 하위 계층 직접 호출 |
| WebFacade | 오케스트레이션, 트랜잭션 경계, 소유 확인(첫 줄) | 비즈니스 규칙 |
| Processor | 비즈니스 로직, Port 호출, ID 생성 | Response DTO 생성 |
| Presenter | `Info → Response` 변환 (ID `long → String` 포함) | 계산·재집계 |

- **`application` 은 `adapter` 구현 타입에 의존하지 않는다.** 허용 예외는 세 가지 — `port/in` 반환 DTO, WebFacade 의 Presenter 주입, MapStruct 매퍼의 Entity 참조. 외부 API 응답 DTO·Feign 래퍼가 application 으로 새면 안 된다.
- out-port 반환은 `QueryResult` 또는 domain model. `Info` 를 포트 밖으로 내보내지 않는다.
- 쓰기 흐름은 `domain → entity → save → entity → domain`.

### 3-1. 트랜잭션

- 읽기 `@Transactional(readOnly = true)`, 쓰기 `@Transactional` 을 WebFacade 에 거는 것이 기본.
- **외부 I/O(OAuth·LLM·공공 API·다른 서비스)가 섞이면 Facade 에 걸지 않는다.** 외부 호출은 트랜잭션 밖에서 하고, DB 구간만 Processor 단위로 좁힌다. 왜 좁혔는지 메서드 주석을 남긴다.

## 4. 서비스 간 호출

- 동기 호출은 Feign + Resilience4j. `adapter/out/client` 뒤에 캡슐화하고 `port/out` 으로만 노출한다. 상세 규칙은 [coding-conventions.md §9](coding-conventions.md#9-서비스-간-호출-feign).
- 서비스 간 API 는 `/internal/v1/**`. 게이트웨이가 라우팅하지 않으며, 받는 쪽이 호출 대상 리소스를 다시 검증한다.

| 호출 | 경로 | 용도 |
|------|------|------|
| auth → surveillance | `GET /internal/v1/districts/{code}` | 행정동 설정 저장 시 코드 검증 |
| auth → surveillance | `DELETE /internal/v1/reporters/{memberId}` | 탈퇴·민감정보 동의 철회 시 원시 보고 파기 |
| surveillance → auth | `POST /internal/v1/notifications/broadcasts` | 안내 발행 알림 팬아웃 |

## 5. 핵심 데이터 흐름

| 단계 | 소유 | 방식 |
|------|------|------|
| ① 주간 보고 | surveillance `report` | JWT scope `report:write` 확인 → `reporter_key` 계산 → `(reporter_key, iso_week)` unique upsert. 행정동 코드는 보고 행에 스냅샷한다 |
| ② 집계 | `aggregate` | 서비스 내 스케줄러가 현재 주를 `GROUP BY` 로 **재계산**한다 (카운터를 누적하지 않아 멱등). 주 마감 시 확정. 참여자 = distinct `reporter_key`, 비율 = 증상 보고 / 참여자 |
| ③ 자료 부족 판정 | `aggregate` | 참여자 < 최소 표본 또는 참여자 급변 → `INSUFFICIENT`. 임계값은 설정 테이블. 참여 급증·반복 보고·기준선 대비 변화는 검토 후보로 적재 |
| ④ 운영자 검토 | `advisory` | `OPERATOR` 역할. 초안에 집계 스냅샷을 복사해 인용값을 고정한다. AI 초안은 스냅샷 수치만 입력으로 넣는 동기 호출 (트랜잭션 밖, timeout 명시) |
| ⑤ 안내 발행 | `advisory` | 상태 `DRAFT → AI_DRAFTED → EDITED → APPROVED → PUBLISHED → RETRACTED`, 전이마다 이력. **자동 발행 경로는 없다** |
| ⑥ 알림 | surveillance → auth | 발행과 같은 트랜잭션에 outbox 행 → 스케줄러가 Feign 으로 전달 (재시도) → auth 가 행정동 × 알림 동의 × 구독으로 발송. 알림 미지원 환경은 공개 안내 API 로 같은 내용을 본다 |

## 6. 개인정보 경계

- **auth DB 에는 증상이 없고, surveillance DB 에는 `member_id` 가 없다.** 회원 정보(이메일·닉네임·프로필 이미지)는 auth 에만 있다. 성명은 받지 않는다.
- `reporter_key = HMAC-SHA256(pepper, memberId)`. pepper 는 Vault 의 surveillance 경로에만 있고 auth 는 모른다. 32자 미만이면 기동 실패, 기동 로그에는 지문만 남긴다. **pepper 는 교체하지 않는다** (교체 = 전 행 재키잉).
- 보고 행 컬럼은 `reporter_key`, `iso_week`, `district_code`, `symptom_mask`, 시각뿐이다. 자유 서술·좌표·성명 컬럼을 만들지 않는다.
- **공개 API 는 집계와 발행된 안내만 읽는다.** 표본이 임계 미만이면 수치·비율 없이 `INSUFFICIENT` 만 내린다. 원시 보고 행을 반환하는 API 는 운영자용에도 두지 않는다.
- `advisory` · `official` · `district` 패키지는 `report` 의 영속 계층을 import 하지 않는다 (ArchUnit 으로 검사).
- 원시 보고는 기준선 산출에 필요한 기간(52주)만 보관하고 스케줄러가 삭제한다. 탈퇴·철회 시 즉시 삭제하고, 익명 집계는 남긴다.
- 동의 상태는 JWT scope 로 전달한다. access token TTL 은 15분 이하, 철회 시 refresh 세션을 폐기한다.

## 7. 참조 데이터

- **행정동**: surveillance `district` 소유, batch 가 적재. SGIS 행정동 코드(`adm_cd`)를 PK 로 쓰고 `valid_from` / `valid_to` 로 개편을 흡수한다. 경계 GeoJSON 은 프론트 정적 자원으로 싣는다.
- **질병관리청 감시 자료**: surveillance `official` 소유, batch 가 적재. `source`, 집계 단위(전국·시도), 기준 주, 수집 시각을 필수 컬럼으로 두고 응답에도 항상 싣는다. 자가보고 지표와 같은 응답 필드에 섞지 않는다.
- 외부 API 원본 응답은 adapter 밖으로 새지 않는다. 쿼터가 있는 API 는 배치 적재 후 DB 조회를 우선한다.

## 8. API 응답

- 공통 봉투 `Response<T>` — `dataHeader { success, resultCode, resultMessage, fieldErrors }` + `dataBody`. 게이트웨이의 JWT 거부 응답(401 · 503 SECURITY_00x)도 같은 봉투다. 라우트 없음(404) · 인스턴스 없음(503) · 업스트림 timeout 같은 게이트웨이 자체 오류는 아직 Spring 기본 형식이다 — FE 는 봉투가 없는 응답을 일시 장애로 다룬다.
- 부모당 0~1개인 하위 리소스의 부재는 200 + `dataBody: null`. 리소스 자체 부재·타인 리소스는 404.
- 인증 API 는 `@PreAuthorize` 를 명시하고 회원 식별은 JWT claim 으로 한다. 클라이언트 헤더로 회원을 받지 않는다.
- 목록은 `SliceResponse` 우선. `totalCount` 는 자르기 전 총계를 Processor 가 계산한다.

## 9. 처음부터 고정하는 계약

바꾸면 저장된 데이터가 깨진다. 변경은 기존 데이터 변환 계획과 함께만 한다.

- `reporter_key` 산출식과 pepper
- 주 정의 — ISO 주, 월요일 시작, KST
- 행정동 코드 체계 — SGIS `adm_cd`
- JWT claim — `sub`(회원 ID), `role`(단일 역할 `USER` / `OPERATOR` / `ADMIN`), `scope`(공백 구분 문자열, 예: `report:write`). 서비스에서는 `hasAuthority('SCOPE_report:write')` 로 검사한다
- `/internal/v1` 경로
