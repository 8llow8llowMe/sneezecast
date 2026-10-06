# 엔티티 설계 (1단계 핵심 기능)

> 핵심 흐름 **가입 → 동네 선택 → 주간 보고 → 동네 현황 → 운영자 안내 발행** 을 돌리는 데 필요한 테이블만 둔다. 뒤로 미룬 테이블은 §6.
> 외부 원천(SGIS · 질병관리청)의 응답 형식 · 호출 조건 · 검증 상태는 [data-api-analysis.md](data-api-analysis.md).
> 컨벤션: PK 는 `id`(Snowflake, `Long`), JPA 연관관계 어노테이션 · DB FK 제약 금지(raw FK + 조회 인덱스), 인덱스 명명 · `@Comment` 규칙은 [coding-conventions.md §8](coding-conventions.md#8-영속성).

## 0. 전체 구조

```text
[auth 스키마]                                   [surveillance 스키마]
member ──1:N── member_consent                   district ◀── batch 적재 (SGIS)
  │──1:1── member_region ─(district_code)──▶      ▲ (district_code, 보고 요청이 싣는다)
  └──1:N── report_purge_request ──DELETE──▶     weekly_report            (reporter_key, member_id 없음)
                                                  │ GROUP BY
        member.id ──HMAC(pepper)──▶ reporter_key  district_weekly_aggregate
                                                  └──1:N── advisory ──1:N── advisory_history
                                                official_surveillance ◀── batch 적재 (질병관리청)
                                                  └── official_source_snapshot (적재 이력)
```

- 공통 감사 컬럼(`created_at`, `updated_at`)은 `persistence-core` `BaseEntity` 가 담당한다 — 아래 표에서 생략한다.
- **서비스 사이에는 조인이 없다.** auth → surveillance 는 행정동 코드 검증 · 보고 파기 내부 API 로만 연결되고, surveillance 는 회원을 `reporter_key` 로만 안다.
- 외부 원천 적재 테이블은 `synced_at`(적재 시각)과 upsert 기준 UK 를 갖는다 (재실행 가능 배치).
- enum 컬럼은 `VARCHAR` + `@Enumerated(STRING)` + **`@JdbcTypeCode(SqlTypes.VARCHAR)`**. 마지막 것이 없으면 Hibernate 6 가 MySQL · H2 에 네이티브 `enum(...)` 타입을 만들어, 값을 하나 추가할 때마다 ALTER 가 필요해진다(dev 의 `ddl-auto: update` 는 기존 컬럼을 고치지 않아 INSERT 가 실패한다). 값 목록은 §7.
- 테이블별 보관 기간은 §8.

---

## 1. auth — 회원

hondigagae auth-service 의 `member` · `member_consent` 와 같은 구조다. 다른 점은 아래뿐이다.

| 항목 | hondigagae | sneezecast | 이유 |
|------|------------|------------|------|
| `member.name` | 있음 | **없음** | 성명 수집 금지 |
| 연령 확인 | `AGE_OVER_14` | `AGE_OVER_19` | 성인 본인만 보고한다 |
| 건강정보 동의 | 없음 | `SENSITIVE_HEALTH_INFO` | 민감정보 별도 동의 (개인정보 보호법 제23조) |
| `member_consent.withdrawn_at` | 없음 | 있음 | 건강정보 동의는 철회할 수 있다 |
| 동의 인덱스 | `idx_member_consent_member_id` | `idx_member_consent_member_id_type` | 항목별 최신 동의 조회 |
| 행정동 | 없음 | `member_region` | 동네 선택 |
| 보고 파기 | 없음 | `report_purge_request` | 다른 서비스에 있는 민감정보 파기를 끝까지 보장한다 |

### 1-1. member

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| email | VARCHAR(100) | N | 이메일. **`uk_member_email`** — 동시 가입 요청에서 중복 계정을 DB 가 막는다 |
| password | VARCHAR(80) | Y | 비밀번호 해시. 카카오로만 로그인하는 회원은 null (비밀번호 최초 설정은 두지 않는다 — #61) |
| nickname | VARCHAR(30) | N | 닉네임 |
| profile_image_url | VARCHAR(500) | Y | 소셜 제공자가 준 외부 프로필 이미지 URL (직접 업로드하면 null) |
| profile_image_key | VARCHAR(512) | Y | 직접 업로드한 프로필 이미지 오브젝트 키 (URL 이 아니라 키를 저장한다) |
| role | VARCHAR(20) | N | `SecurityRole` — USER / OPERATOR / ADMIN. JWT `role` claim 과 같은 값 |
| provider | VARCHAR(20) | Y | `OAuthProvider` — KAKAO. null 이면 이메일 계정만. KAKAO 면 카카오 로그인 가능(비밀번호가 있으면 이메일 로그인도 — 기존 이메일 계정에 카카오를 연결한 경우) |
| status | VARCHAR(20) | N | `MemberStatus` — ACTIVE / WITHDRAWN / SUSPENDED |
| withdrawn_at | TIMESTAMP | Y | 탈퇴 시각. 30일 보존 후 파기 스케줄러의 판정 기준 |

- 인증 코드 · 로그인 시도 횟수 · OAuth state · refresh 토큰 · 세션은 테이블이 아니라 Redis(TTL) 다 ([modules.md](modules.md#serviceauth-service)).
- 운영자 계정은 따로 두지 않는다. `role` 이 OPERATOR 인 회원이다.
- **탈퇴 회원 hard delete 는 그 회원의 `report_purge_request` 가 모두 완료된 뒤에만 한다** (§1-5). 회원 행이 먼저 사라지면 `memberId` 를 잃어 `reporter_key` 를 다시 계산할 수 없고, 보고가 52주 동안 남는다.
- **탈퇴 즉시 같은 이메일로 재가입할 수 있다** (2026-10-01 결정). 탈퇴할 때 `email` 을 다이제스트로 바꿔 원문을 비운다(hondigagae `WithdrawnEmailHasher` 방식) — `uk_member_email` 을 풀어 주면서 회원 행은 보고 파기가 끝날 때까지 남긴다. 보고 파기는 `memberId` 기준이라 영향이 없다. 구현은 #59.

### 1-2. member_consent — 동의 이력

회원당 항목당 **여러 행이 쌓인다** (문서 개정 시 재동의, 철회 후 재동의). 그래서 `(member_id, type)` 에 unique 를 걸지 않는다.

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| member_id | BIGINT | N | 회원 아이디 (FK: member.id) |
| type | VARCHAR(30) | N | `ConsentType` — §1-3 |
| document_version | VARCHAR(20) | N | 동의한 근거 문서의 버전 (예: `2026-10-01`). 정본은 프론트 legal 상수, 백엔드는 `legal.*-version` 설정으로 맞춘다 |
| agreed_at | TIMESTAMP | N | 동의 시각. `created_at` 과 따로 둔다 — 감사 대상은 행이 생긴 시각이 아니라 동의한 시각이다 |
| withdrawn_at | TIMESTAMP | Y | 철회 시각. 철회 가능한 항목만 채운다 |

- 인덱스: `idx_member_consent_member_id_type`
- **현재 유효한 동의** = 항목별 `agreed_at` 최대 행이고 `withdrawn_at` 이 null 이며 `document_version` 이 현재 설정 버전과 같은 것. **예외: `AGE_OVER_19` 는 버전을 보지 않는다**(사실 확인이라 약관 개정 때 다시 받지 않는다 — 2026-10-02 결정, 기록에는 처음 확인 때의 약관 버전이 남는다).
- 운영 흐름
  - **문서 개정**: `legal.*-version` 설정만 올린다. 이전 버전 동의자는 "유효 동의 없음" 이 되어 다음 로그인 때 재동의 화면으로 간다. 기존 행은 고치지 않는다.
  - **철회**: 해당 행에 `withdrawn_at` 을 채운다(철회 대상은 최신 행이 철회되지 않은 것 — 버전과 무관, 옛 버전 동의로 쓴 보고도 파기해야 해서). 건강정보 동의 철회는 같은 트랜잭션에서 `report_purge_request` 를 만들고(미완료 요청이 이미 있으면 만들지 않는다), 커밋 뒤 refresh 세션을 전부 폐기하고 그 세션들 · 요청 기기의 access token `jti` 를 블랙리스트에 올린다 (§1-5).
  - **재동의**: 새 행을 추가한다. 미완료 파기 요청이 있으면 완료될 때까지 `report:write` scope 를 발급하지 않는다 (§1-5 2차 파기가 새 보고를 지우지 않게).

### 1-3. 동의 항목 (`ConsentType`)

| 값 | 의미 | 필수 | 철회 | `document_version` 에 넣는 것 |
|----|------|------|------|-------------------------------|
| TERMS_OF_SERVICE | 이용약관 | 가입 필수 | 탈퇴로만 | 이용약관 버전 |
| PRIVACY_POLICY | 개인정보 수집·이용 (이메일 · 닉네임 · 프로필 이미지 · 행정동) | 가입 필수 | 탈퇴로만 | 개인정보 처리방침 버전 |
| SENSITIVE_HEALTH_INFO | **민감정보(건강정보) 별도 동의** — 증상 보고 | 보고 필수 (가입은 가능) | **가능** | 민감정보 수집·이용 동의서 버전 |
| AGE_OVER_19 | 만 19세 이상 확인 (자기신고) | 가입 필수 | 없음 (사실 확인이라 철회 개념이 없다) | 성인 기준을 정한 이용약관 버전 (약관 개정 때 다시 받지 않는다 — 유효 판정에서 버전을 보지 않음) |

- **개인정보 보호법 제23조 민감정보는 증상 보고뿐이다.** 이메일 · 닉네임 · 프로필 이미지는 일반 개인정보라 PRIVACY_POLICY 로 받는다.
- 건강정보 동의는 가입 동의와 **화면 · 체크를 분리**한다 (별도 동의 요건). 동의 여부는 JWT `scope` 의 `report:write` 로 전달된다.
- 알림 수신 동의는 푸시(2단계)와 함께 `PUSH_NOTIFICATION` 으로 추가한다. 필수 여부는 enum 이 아니라 요청 검증 · 가입 로직이 정한다.

### 1-4. member_region — 선택한 행정동

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| member_id | BIGINT | N | 회원 아이디 (FK: member.id). **`uk_member_region_member_id`** |
| district_code | VARCHAR(8) | N | 행정동 코드 (SGIS 8자리, surveillance `district.code`). 저장 전 내부 API 로 **현행 코드인지** 검증한다 |

- `member` 컬럼이 아니라 테이블로 둔 이유: `region` 컨텍스트가 소유하고, 관심 지역을 여럿 두게 되면 unique 만 풀어 1:N 으로 넘어간다.
- 행정동이 개편돼도 이 행은 자동으로 바꾸지 않는다. 폐지된 코드면 화면에서 다시 선택하게 한다 (§3-1).
- 저장은 `PUT /api/v1/members/me/region` 의 회원당 1행 upsert 이고 **현재 값만** 둔다(변경 이력 없음). 현행 코드만 저장할 수 있다. 이름 · 폐지 여부는 저장하지 않고 조회할 때마다 surveillance 내부 API 로 읽어 `abolished` 로 내린다.
- FE 는 주간 보고 요청에 이 코드를 실어 보낸다. surveillance 는 요청 코드를 `district` 로 다시 검증하고 보고 행에 **복사**한다 (§2-1) — 회원이 동네를 바꿔도 지난 보고의 지역은 바뀌지 않는다.

### 1-5. report_purge_request — 원시 보고 파기 요청

탈퇴 · 건강정보 동의 철회 때 surveillance 원시 보고를 지우라는 요청이다. Feign 호출 한 번에 기대지 않고, **완료될 때까지 스케줄러가 다시 부른다.**

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| member_id | BIGINT | N | 회원 아이디 (FK: member.id) |
| reason | VARCHAR(30) | N | `PurgeReason` — WITHDRAWAL / HEALTH_CONSENT_WITHDRAWN |
| requested_at | TIMESTAMP | N | 요청 시각 (탈퇴 · 철회 시각) |
| first_purged_at | TIMESTAMP | Y | 첫 파기 성공 시각 |
| completed_at | TIMESTAMP | Y | 완료 시각. **null = 미완료** |
| attempt_count | INT | N | 호출 시도 횟수, default 0 (`@ColumnDefault("0")`) |
| last_error | VARCHAR(200) | Y | 마지막 실패 사유 (예외 코드 · 상태 코드만. 응답 본문을 넣지 않는다) |

- 인덱스: `idx_report_purge_request_member_id`, `idx_report_purge_request_completed_at`
- **완료 조건: 호출 시작 시각이 `requested_at + access token TTL(15분) + 여유(설정, 기본 5분)` 이후인 파기 호출이 성공했을 때.** 철회 직전에 발급된 다른 기기의 access token 에는 `report:write` 가 최대 15분 남는다. 1차 파기 뒤 그 토큰으로 들어온 보고를 2차 파기가 지운다. 여유는 만료 직전에 검증된 요청이 늦게 커밋되는 경우와 서버 간 시계 오차를 흡수한다.
- 스케줄러(auth, 5분 주기)는 `completed_at is null` 행에 `DELETE /internal/v1/reporters/{memberId}` 를 부른다. **받는 쪽은 멱등이다** — 지울 행이 0건이어도 204.
- `attempt_count` 가 임계값(설정)을 넘으면 경보를 낸다. 행을 지우거나 포기 상태로 두지 않는다 (파기는 반드시 끝나야 한다).

---

## 2. surveillance — 자가보고와 안내

회원 식별자는 **`reporter_key` 뿐**이다 (`HMAC-SHA256(pepper, memberId)`, [architecture-guide.md §6](architecture-guide.md#6-개인정보-경계)). 예외는 운영자 감사 컬럼 `advisory_history.operator_id` 다.

> **알려진 한계**: surveillance 는 pepper 와 운영자 `operator_id` 를 함께 가지므로, DB 와 pepper 를 동시에 가진 사람은 운영자 본인의 보고를 찾을 수 있다. 1단계는 surveillance DB 계정 · Vault pepper 경로의 접근 권한 분리로 막고, 운영자 수가 늘면 감사 기록을 auth 로 옮기는 안을 검토한다.

### 2-1. weekly_report — 주간 건강 보고 (원시)

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| reporter_key | CHAR(64) | N | 가명 보고자 키 (HMAC-SHA256 hex) |
| iso_week | CHAR(8) | N | 보고 주 `YYYY-Www` (ISO 주, 월요일 시작, KST). 문자열 정렬 = 시간 순 |
| district_code | VARCHAR(8) | N | 보고 행정동 코드. 같은 주 수정이면 마지막 요청 값으로 바뀐다 |
| symptom_mask | TINYINT | N | 증상군 비트 (§7 `SymptomGroup`). **0 = 증상 없음** |
| revision_count | SMALLINT | N | 같은 주 수정 횟수, default 0. 반복 보고 검토 후보의 근거 |

- **`uk_weekly_report_reporter_key_iso_week`** — 같은 사람의 같은 주 보고는 한 행(수정은 upsert). 집계에서 한 번만 세는 불변식을 DB 가 보장한다.
- 인덱스: `idx_weekly_report_iso_week_district_code` (집계 `GROUP BY`)
- **증상군 단위로만 저장한다** (호흡기 · 장관). 개별 증상(발열 · 기침 …)은 화면 안내 문구일 뿐 저장하지 않는다 — 집계에 쓰지 않는 값을 모으지 않는다.
- **주 경계 규칙**
  - `iso_week` 는 **서버가** 요청 시각(KST)으로 정한다. 요청에 주 값을 받지 않는다.
  - 연도는 `IsoFields.WEEK_BASED_YEAR`, 주는 `IsoFields.WEEK_OF_WEEK_BASED_YEAR` 로 구한다. `DateTimeFormatter` 의 `YYYY` · `ww` 패턴은 로캘에 따라 주 정의가 바뀌므로 쓰지 않는다 (12월 29일 ~ 1월 3일에 연도가 어긋난다).
  - **현재 주만 쓸 수 있다.** 지난 주 보고 · 수정은 거절한다 — 마감된 집계와 원시 보고가 어긋나지 않게.
- **행정동**: 요청의 `districtCode` 를 `district` 에서 현행 코드(`valid_to_year is null`)인지 확인한다. 폐지 · 없는 코드는 400.
- **동시 요청**: 같은 사람의 동시 제출은 UK 위반으로 한쪽이 실패한다. 저장 어댑터가 `saveAndFlush` 로 그 자리에서 드러내고 **`uk_weekly_report_reporter_key_iso_week` 위반만 `REPORT_001`(409) 로 바꾼다** (다른 무결성 위반은 그대로). 실패한 트랜잭션은 rollback-only 라 그 안에서 다시 시도할 수 없다 — **WebFacade(트랜잭션 밖)가 `REPORT_001` 을 잡아 새 트랜잭션으로 한 번 다시** 부르고, 이번에는 update 경로를 탄다 (네이티브 upsert 는 컨벤션상 쓰지 않는다). 고치려던 행이 그 사이 취소돼 수정이 0건이어도 같은 `REPORT_001` 로 보고 같은 재시도가 첫 보고로 저장한다. 재시도도 지면 409 `REPORT_001` 이다.
- 수정 시 `revision_count` 는 읽어서 더하지 않고 `revision_count = revision_count + 1` JPQL update 로 올린다 (동시 수정에서 증가분이 사라지지 않게). SMALLINT 상한(32767)에서는 더 올리지 않는다(포화, `CASE WHEN`) — 남용에서도 수정이 범위 초과 500 이 되지 않는다. 벌크 갱신은 Auditing 을 타지 않으므로 같은 쿼리에서 `updated_at` 을 `Clock` 빈 시각으로 쓰고, 호출자 트랜잭션을 필수로 한다.
- 주 계산 · 수정 시각은 `Clock` 빈(KST)에서 받는다 (`ReportWeekCalculator`). 생성 시각(`created_at`)은 Auditing(JVM 기본 시간대 시스템 시각)이라, 고정 Clock 을 쓰는 테스트에서는 두 시각의 선후를 비교하지 않는다. **보고 취소는 행 삭제다** — 취소 표시 컬럼을 두지 않고, 지울 행이 없어도 실패하지 않는다(멱등).
- 알려진 한계: 취소 뒤 같은 주에 다시 제출하면 새 행이라 `revision_count` 가 0 부터 다시 센다. 반복 보고 검토 후보가 "취소 → 재제출" 반복을 놓칠 수 있다. 운영자 검토 설계(집계 이슈)에서 신호가 더 필요하면 취소 횟수를 따로 세는 안을 검토한다.
- 이 테이블 밖으로 행을 내보내는 API 는 없다. 조회는 본인 이번 주 보고(`reporter_key` 일치)와 집계 스케줄러뿐이다.
- 보관: 52주 지나면 삭제 스케줄러가 지운다. 탈퇴 · 건강정보 동의 철회 시 해당 `reporter_key` 행을 삭제한다 (§1-5).

### 2-2. district_weekly_aggregate — 행정동 × 주 집계

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| district_code | VARCHAR(8) | N | 행정동 코드 |
| iso_week | CHAR(8) | N | 집계 주 |
| participant_count | INT | N | 참여자 수 (= 그 주 보고 행 수, 증상 없음 포함). 분모 |
| symptomatic_count | INT | N | 증상군 하나 이상 보고 수 |
| respiratory_count | INT | N | 호흡기 증상군 보고 수 |
| enteric_count | INT | N | 장관 증상군 보고 수 |
| revised_report_count | INT | N | 그 주에 한 번 이상 수정된 보고 수. 반복 보고 검토 후보의 근거 |
| level | VARCHAR(20) | N | `AggregateLevel` — GOOD / NORMAL / CAUTION / INSUFFICIENT |
| insufficient_reason | VARCHAR(20) | Y | `InsufficientReason` — LOW_SAMPLE / UNSTABLE. level 이 INSUFFICIENT 일 때만 |
| rule_version | VARCHAR(20) | N | 판정에 쓴 규칙 버전 (설정 `aggregate.rule-version`). 규칙이 바뀌어도 과거 판정을 설명할 수 있게 남긴다 |
| calculated_at | TIMESTAMP | N | 마지막 재계산 시각 |
| finalized_at | TIMESTAMP | Y | 주 마감 확정 시각. 확정 후에는 재계산하지 않는다 |

- **`uk_district_weekly_aggregate_district_code_iso_week`** — 스케줄러는 현재 주를 `GROUP BY` 로 다시 세서 이 키로 upsert 한다 (카운터 누적이 아니라 멱등).
- **재계산 결과에 없는 행**(동을 바꾼 수정 · 파기로 보고가 0건이 된 동)은 수치를 0, level 을 INSUFFICIENT(LOW_SAMPLE) 로 덮어쓴다. 결과에 없다고 건너뛰면 이전 수치가 남는다.
- **행이 없는 동**은 조회 API 가 INSUFFICIENT 로 응답한다 (보고가 한 번도 없던 동).
- **판정 (1단계)**: 참여자 < 최소 표본 → LOW_SAMPLE, 전주 대비 참여자 급변 → UNSTABLE (전주 행과 비교한다), 그 밖은 증상 보고 비율 임계값으로 GOOD / NORMAL / CAUTION. 임계값은 설정값이고 기준선 대비 판정은 2단계다.
  - 전주 행이 없거나 전주 참여자 < 최소 표본이면 UNSTABLE 을 보지 않는다 (비교할 기준이 없다). 판정 순서는 LOW_SAMPLE → UNSTABLE → 비율이다.
- **마감**: 월요일 00:10(KST) 마감 잡이 지난 주를 마지막으로 재계산하고 `finalized_at` 을 채운다. 지난 주 보고는 받지 않으므로(§2-1) 마감 뒤 원시 보고와 어긋나지 않는다.
- 비율은 저장하지 않고 조회 시 `count / participant_count` 로 계산한다. **INSUFFICIENT 면 API 는 수치 · 비율을 내리지 않는다** — 행에 숫자가 있어도 응답에서 뺀다.
- **검토 후보 (1단계)**: 운영자 화면이 조회 시 계산한다 — 참여 급증(전주 대비 `participant_count`), 반복 보고(`revised_report_count / participant_count`). 기준선 대비 변화와 후보 적재 테이블(`review_signal`)은 2단계다.
- 집계 · 마감 스케줄러는 **한 번에 하나만** 돈다. 1단계는 surveillance 인스턴스 1개로 운영하고, 늘리면 ShedLock 을 붙인다.
- 원시 보고가 52주 뒤 지워져도 이 행은 남는다 (익명 집계).

### 2-3. advisory — 예방 안내

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| district_code | VARCHAR(8) | N | 대상 행정동 |
| iso_week | CHAR(8) | N | 근거 집계 주 |
| aggregate_id | BIGINT | N | 근거 집계 (FK: district_weekly_aggregate.id) |
| status | VARCHAR(20) | N | `AdvisoryStatus` — §2-5 |
| title | VARCHAR(100) | N | 제목 (현재본) |
| body | TEXT | N | 본문 (현재본) |
| cited_participant_count | INT | N | 인용 참여자 수 — 초안 생성 시 집계에서 **복사해 고정** |
| cited_symptomatic_count | INT | N | 인용 증상 보고 수 |
| cited_respiratory_count | INT | N | 인용 호흡기 보고 수 |
| cited_enteric_count | INT | N | 인용 장관 보고 수 |
| cited_level | VARCHAR(20) | N | 인용 단계. **INSUFFICIENT 는 들어갈 수 없다** |
| cited_rule_version | VARCHAR(20) | N | 인용 집계의 판정 규칙 버전 |
| published_at | TIMESTAMP | Y | 발행 시각 |
| retracted_at | TIMESTAMP | Y | 철회 시각 |
| version | BIGINT | N | 낙관적 락 (`@Version`). 운영자 두 명이 동시에 고쳐 한쪽 수정이 사라지는 것을 막는다 |

- 인덱스: `idx_advisory_district_code_status_published_at` (공개 목록), `idx_advisory_aggregate_id`
- **초안 생성 전제**: 근거 집계가 **마감됐고**(`finalized_at` not null) level 이 INSUFFICIENT 가 아니어야 한다. "충분한 자료가 있을 때에만 예방 정보를 전한다" 는 불변식을 초안 단계에서 막는다.
- 인용값을 복사해 두는 이유: 운영자가 검토한 수치와 발행문 수치가 같아야 한다. 집계가 재계산돼도 안내는 바뀌지 않는다.
- **자동 발행 경로는 없다.** PUBLISHED 로 가는 전이는 OPERATOR 요청으로만 일어난다.
- 공개 API 는 PUBLISHED 만 읽는다.

### 2-4. advisory_history — 상태 전이 · 수정 이력

전이 · 수정마다 한 행. 수정하지 않고 쌓기만 한다.

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| advisory_id | BIGINT | N | 안내 (FK: advisory.id) |
| from_status | VARCHAR(20) | Y | 이전 상태 (생성이면 null) |
| to_status | VARCHAR(20) | N | 바뀐 상태 |
| title | VARCHAR(100) | N | 이 시점의 제목 |
| body | TEXT | N | 이 시점의 본문 — 무엇이 승인 · 발행됐는지 복원한다 |
| operator_id | BIGINT | N | 요청한 운영자 (auth `member.id`). AI 초안도 **요청한 운영자**를 남긴다 |
| ai_model | VARCHAR(60) | Y | AI 초안을 만든 모델 id. AI_DRAFTED 전이에만 |
| prompt_version | VARCHAR(20) | Y | AI 초안에 쓴 프롬프트 버전 (설정). AI_DRAFTED 전이에만 |
| memo | VARCHAR(500) | Y | 수정 · 철회 사유 |

- 인덱스: `idx_advisory_history_advisory_id`
- `created_at` 이 전이 시각이다.

### 2-5. advisory 상태 전이

| 현재 | 다음 | 누가 | 조건 |
|------|------|------|------|
| (없음) | DRAFT | OPERATOR | §2-3 초안 생성 전제 |
| DRAFT | AI_DRAFTED | OPERATOR 요청 → AI | 입력은 인용값 스냅샷뿐 (트랜잭션 밖 동기 호출, timeout 명시) |
| DRAFT · AI_DRAFTED | EDITED | OPERATOR | 본문 · 제목 수정 |
| DRAFT · AI_DRAFTED · EDITED | APPROVED | OPERATOR | 운영자가 현재 본문을 검토 · 승인 |
| APPROVED | EDITED | OPERATOR | **승인 뒤 수정하면 다시 승인받아야 한다** |
| APPROVED | PUBLISHED | OPERATOR | 발행. `published_at` 기록 |
| PUBLISHED | RETRACTED | OPERATOR | 철회. `retracted_at` · 사유(`memo`) 기록 |

- 표에 없는 전이는 409. PUBLISHED 본문은 고치지 않는다 — 철회하고 새 안내를 만든다.
- RETRACTED 는 끝 상태다.

---

## 3. surveillance — 외부 원천 적재

원천 형식 · 호출 조건 · 검증 상태는 [data-api-analysis.md](data-api-analysis.md). 테이블은 **batch-service 가 쓰고 surveillance 가 읽는다** (구조의 정본은 surveillance). 잡 · 주기는 §4.

### 3-1. district — 행정동 마스터 (SGIS)

원천: SGIS `boundary/hadmarea.geojson` (`year` 지정, 시도마다 `low_search=2`) — 기준 연도별 스냅샷. 시도 · 시군구 이름은 `addr/stage.json` 단계 조회로 채운다 ([data-api-analysis.md §1](data-api-analysis.md#1-sgis-오픈api-통계청--행정동-마스터)).

| 컬럼 | 타입 | Null | 원천 필드 | 설명 |
|------|------|------|-----------|------|
| id | BIGINT | N | — | PK. SGIS 코드를 숫자로 바꾼 값 (batch 가 결정적으로 만든다 — district 는 Snowflake 를 쓰지 않는다, hondigagae `PlaceIdFactory` 와 같은 이유) |
| code | VARCHAR(8) | N | adm_cd | SGIS 읍면동 코드 8자리. **`uk_district_code`** |
| name | VARCHAR(50) | N | adm_nm 마지막 토큰 | 읍면동 이름 (예: 가락1동). `adm_nm` 은 전체 주소(`서울특별시 송파구 가락1동`)라 마지막 토큰만 쓴다 |
| sido_code | VARCHAR(2) | N | adm_cd 앞 2자리 | SGIS 시도 코드 |
| sido_name | VARCHAR(30) | N | stage.json addr_name | 시도 이름 |
| sigungu_code | VARCHAR(5) | N | adm_cd 앞 5자리 | SGIS 시군구 코드 |
| sigungu_name | VARCHAR(30) | N | stage.json addr_name | 시군구 이름 |
| valid_from_year | SMALLINT | N | (적재 year) | 처음 확인된 SGIS 기준 연도 |
| valid_to_year | SMALLINT | Y | (가공) | 새 기준 연도 스냅샷에 없으면 직전 연도를 채운다. **null = 현행** |
| last_seen_year | SMALLINT | N | (적재 year) | 이 코드가 마지막으로 확인된 SGIS 기준 연도. 적재마다 갱신하고 폐지는 건드리지 않는다 (연도 역행 판정용) |
| synced_at | TIMESTAMP | N | — | 마지막 적재 시각 |

- 인덱스: `idx_district_sigungu_code` (시군구별 선택 목록)
- **SGIS 코드는 행안부 행정동 코드(10자리)와 번호 체계가 다르다.** 자릿수를 잘라 변환하지 않는다. 행안부 코드가 필요해지면 매핑 컬럼을 출처 · 기준일과 함께 추가한다.
- 적재는 멱등 upsert (키 `code`). **이름으로 맞추지 않는다** — 분동하면 이름이 같은 채 코드가 바뀐다 (2025 부산 녹산동 `21120560` → `21120561`). 폐지된 동은 지우지 않고 `valid_to_year` 만 채운다 — 과거 보고 · 집계 행이 그 코드를 참조한다. **폐지됐던 코드가 새 스냅샷에 다시 나오면** `valid_to_year` 를 null 로 되돌리고 이름을 갱신한다.
- `valid_from_year` 는 첫 적재 때 그 적재 연도(예: 2025)다 — 실제 신설 연도가 아니라 "우리가 처음 본 연도" 다.
- **연도 역행 금지**: 적재 `year` 가 마지막 적재 연도 `MAX(last_seen_year)` 보다 작으면 아무것도 쓰지 않고 실패한다(`YEAR_REGRESSION`). `valid_from_year` · `valid_to_year` 로는 셀 수 없다 — 폐지만 있던 해는 `valid_from_year` 에 남지 않고, 재등장한 코드는 `valid_to_year` 를 지운다. 과거 연도로 다시 돌리면 그 뒤에 생긴 동은 폐지되고 그 뒤에 폐지된 동은 되살아난다. 테이블이 비면 제한 없음, 같은 연도 재실행은 허용 (멱등).
- **보호 규칙**: 직전 현행 코드 중 새 스냅샷에서 사라지는 비율이 임계값(설정, 기본 2%)을 넘으면 폐지 처리를 하지 않고 잡을 실패시킨다. 2024 → 2025 는 3,559개 중 3개(0.1%)였다. 잘린 응답과 광주 · 전남 통합 같은 대규모 코드 변경은 운영자가 확인한 뒤 잡 파라미터 `allowMassRetire=true` 로 다시 돌린다 — 폐지되면 그 동을 고른 회원 전원이 재선택해야 하기 때문이다.
- 경계 GeoJSON 은 테이블에 넣지 않는다. 프론트 정적 자원이다 (UTM-K EPSG:5179 → 웹 지도용 4326 으로 변환해서 싣는다).
- 폐지된 코드를 가진 `member_region` 은 화면에서 재선택을 요구한다. 신 · 구 코드 연계표 API 는 확인되지 않아 자동 이관하지 않는다.

### 3-2. official_surveillance — 질병관리청 감시 자료

원천 두 가지를 한 테이블에 담는다. **시민 자가보고와 섞지 않는다** — 응답도 별도 필드로 내리고, 출처 · 집계 단위 · 기준 기간 · 수집 시각을 항상 싣는다.

| `source` | 원천 | `program` | 내용 |
|----------|------|-----------|------|
| KDCA_NOTIFIABLE | 공공데이터포털 전수신고 감염병 발생현황 API (15139178) | NOTIFIABLE | 전국 주별 · 시도 연별 발생 수 |
| KDCA_SENTINEL | 감염병포털 표본감시 통계 (공공데이터포털 15053801 이 가리키는 원천) | INFLUENZA_ILI | 인플루엔자 의사환자 분율 (외래 1,000명당), 연령대별 |
| KDCA_SENTINEL | 〃 | ARI | 급성호흡기감염증 병원체별 신고 수 |
| KDCA_SENTINEL | 〃 | ENTERIC | 장관감염증 병원체별 신고 수 |

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| source | VARCHAR(30) | N | `OfficialSource` |
| program | VARCHAR(20) | N | `OfficialProgram` — 위 표 |
| disease_key | VARCHAR(100) | N | 정규화 키. 표본감시는 **포털 병원체 코드**(예: `ND0715` 노로바이러스, 합계는 `TOTAL`, 인플루엔자 분율은 `ILI`), 전수신고는 원천에 코드가 없어 감염병명에서 앞의 `@` 표식을 뗀 값 |
| disease_name | VARCHAR(100) | N | 표시 이름 (원천 그대로) |
| disease_group | VARCHAR(20) | Y | 원천 분류 — 전수신고 `icdGroupNm` 을 `제N급` 으로 맞춘 값(원천은 오퍼레이션마다 `제2급` / `2급`), 표본감시 세균 / 바이러스 / 원충 |
| metric | VARCHAR(30) | N | `OfficialMetric` — CASE_COUNT / INCIDENCE_PER_100K / ILI_PER_1000 |
| age_group | VARCHAR(20) | N | `OfficialAgeGroup`, default ALL. 인플루엔자는 연령대별 행만 온다 |
| region_level | VARCHAR(10) | N | `OfficialRegionLevel` — NATION / SIDO |
| region_code | VARCHAR(4) | N | **질병관리청 시도 코드**(01 서울 … 17 세종, 18 전남광주 — SGIS 코드와 다르다). 전국이면 `00` |
| region_name | VARCHAR(30) | N | 지역 이름 (전국이면 `전국`) |
| period_type | VARCHAR(10) | N | `OfficialPeriodType` — WEEK / YEAR |
| period_year | SMALLINT | N | 원천 연도 |
| period_week | TINYINT | N | 원천 주차 (질병관리청 주차 그대로). YEAR 면 0 |
| period_start | DATE | N | 기간 시작일 (가공). WEEK 는 [data-api-analysis.md §5](data-api-analysis.md#5-주차-정의) 규칙, YEAR 는 1월 1일 |
| period_end | DATE | N | 기간 종료일 (가공). WEEK 는 시작일 + 6일, YEAR 는 12월 31일 |
| metric_value | DECIMAL(12,2) | Y | 값 (단위는 `metric`). **원천이 빈 칸이면 null** (0 과 구분한다). `value` 는 H2 예약어라 피했다 |
| source_snapshot_id | BIGINT | N | 마지막으로 이 값을 쓴 실행 (FK: official_source_snapshot.id) |
| synced_at | TIMESTAMP | N | 마지막 적재 시각 (응답 · 화면의 "수집 시각") |

- **`uk_official_surveillance_natural_key`** = `(source, program, disease_key, metric, age_group, region_level, region_code, period_type, period_year, period_week)` — 멱등 upsert. 키에는 원천이 준 정수 연도 · 주차만 넣고, 가공한 날짜는 넣지 않는다 (주차 → 날짜 규칙이 바뀌어도 행이 겹치지 않게).
- 인덱스: `idx_official_surveillance_program_period_start` (최근 기간 조회), `idx_official_surveillance_source_snapshot_id` (FK 조회)
- **인플루엔자 절기 → 연도**: 절기 `startYear`–`endYear` 의 36 ~ 52(53)주는 `period_year = startYear`, 01 ~ 35주는 `endYear` 로 넣는다. 열 제목(`headerList`)의 주차만 보고 연도를 정하면 1년 어긋난다.
- 원천은 **잠정 통계**라 과거 주 값이 바뀐다. 적재 잡은 최근 N주(설정)를 다시 받아 덮어쓴다. 값 변경 이력은 두지 않고, 어느 실행이 마지막으로 썼는지만 `source_snapshot_id` 로 남긴다.
- **전수신고 주별은 신고 지연으로 최근 주 값이 계속 늘어난다** (진행 중인 주까지 온다, [data-api-analysis.md §2-3](data-api-analysis.md#2-3-신고-지연--최근-주는-값이-계속-늘어난다)). 적재는 하되, 화면의 기준 주는 **현재 주에서 2주 전**(설정 `official.notifiable.display-lag-weeks`, 기본 2)으로 하고 그보다 최근 주는 보이지 않는다.
- 전수신고 응답의 `계` 행(주별 합)은 적재하지 않는다. 시도 값을 더해 전국 · 권역을 만들지 않는다 (광주 · 전남 · 전남광주 코드가 기간에 따라 겹친다).
- 표시 대상(감염병 · 병원체 목록)은 1단계에서 설정값으로 둔다.
- 라이선스 **공공누리 제4유형(출처표시 · 상업적 이용금지 · 변경금지)** — 화면에 출처를 밝히고 원값을 그대로 보인다. 파생값(예: 전주 대비)을 보이면 파생값임을 밝힌다.
- 정본은 surveillance `domainlayer/official` 의 `OfficialSurveillanceEntity` (§3-3 은 `OfficialSourceSnapshotEntity`). `id` 는 batch 가 Snowflake 로 할당하고 surveillance 는 이 두 테이블을 `save` 하지 않으므로 `Persistable` 을 구현하지 않는다 — [coding-conventions.md §8-1](coding-conventions.md#8-1-엔티티) 의 판정은 `save` 경로에서만 의미가 있다 (#74 가 `BaseEntity` 로 올리면 함께 적용된다). enum 은 같은 패키지 `domain/enums` 에 두고, 저장 값(`name()`)이 batch 와의 DB 계약이라 테스트로 고정한다.

### 3-3. official_source_snapshot — 외부 원천 적재 이력

실행 한 번의 조회 조건 하나에 한 행 — 원천 요청 하나가 한 행이고, 페이지를 넘긴 응답은 한 행으로 합친다 (전수신고는 실행당 약 74행 — `/PeriodBasic` 연도마다 1 + `/Region` 연도 × 지표 × 시도마다 1, [data-api-analysis.md §2-5](data-api-analysis.md#2-5-실행당-호출-수-notifiableimportjob)). **수정하지 않고 쌓기만 한다.** 운영자가 "언제 무슨 조건으로 무엇을 받았고 몇 건을 넣었나" 를 되짚는 근거다 (hondigagae `import_source_snapshot` 과 같은 역할). 멱등은 `official_surveillance` UK 가 보장하므로, 1단계는 "같은 내용이면 건너뛰기" 를 두지 않는다 — 조회 범위가 매주 움직여서(최근 8주) 비교 기준이 되지 않는다.

| 컬럼 | 타입 | Null | 설명 |
|------|------|------|------|
| id | BIGINT | N | PK (Snowflake) |
| source | VARCHAR(30) | N | `OfficialSource` |
| program | VARCHAR(20) | N | `OfficialProgram` |
| request_key | VARCHAR(200) | N | 조회 조건 요약 (예: `ari:2026-31~2026-38:age=ALL`) |
| channel | VARCHAR(20) | N | `IngestChannel` — OPEN_API / PORTAL_JSON |
| content_sha256 | CHAR(64) | Y | 받은 본문의 해시 (추적용). 실패해서 본문이 없으면 null |
| byte_length | INT | N | 받은 바이트 수 |
| row_count | INT | N | 파싱한 원천 행 수 |
| imported_count | INT | N | upsert 한 행 수 |
| status | VARCHAR(20) | N | `IngestStatus` — IMPORTED / FAILED |
| error_code | VARCHAR(50) | Y | 실패 코드 — 잡 ErrorCode 의 `code` 값 (예: 쓰기 실패 `OFFICIAL_INGEST_003`). 로그 · 예외 메시지의 `[코드]` 와 같아 함께 찾을 수 있다 |
| run_started_at | TIMESTAMP | N | 실행 시작 시각 (JobParameter `runAt`) |

- 인덱스: `idx_official_source_snapshot_request_key_created_at` (조건별 최신 실행)
- FAILED 행은 원천 데이터를 쓰지 않는다. 실패해도 기존 `official_surveillance` 값은 그대로다.
- 쓰기 순서: 한 트랜잭션에서 IMPORTED 행 INSERT → `official_surveillance` upsert(`source_snapshot_id` = 그 행). 실패하면 둘 다 롤백하고 별도 트랜잭션으로 FAILED 행을 남긴다 (batch `OfficialIngestProcessor`, [modules.md](modules.md#servicebatch-service)).

---

## 4. batch-service — 적재 잡과 스케줄

### 4-1. 잡 목록

| 잡 | 원천 | 기본 주기 (KST) | 조회 범위 | upsert 키 | 비고 |
|----|------|-----------------|-----------|-----------|------|
| `districtImportJob` | SGIS 경계 · 단계별 주소 | **수동** (연 1회, SGIS 기준 연도 공개 후) | 지정한 `year` 전체 (경계 시도별 17회, 약 33MB + 단계별 주소 18회) | district.code | 사라지는 코드 비율이 임계값을 넘으면 폐지 처리 없이 실패 (§3-1) |
| `notifiableImportJob` | 전수신고 API `PeriodBasic`(주) · `Region`(연) | 매주 화 05:00 | 올해 + 전년 (`year` 로 백필) | §3-2 UK | 실행당 약 74건 (시도마다 1회) — 개발계정 일 1,000건의 7%. 요청별로 격리하고 키 · 게이트웨이 · 호출 상한 · DB 오류에서만 멈춘다. 실패가 있으면 `NOTIFIABLE_IMPORT_020` 으로 FAILED ([modules.md](modules.md#servicebatch-service)) |
| `sentinelImportJob` | 감염병포털 표본감시 (인플루엔자 · 급성호흡기 · 장관) | 매주 금 06:00 | 최근 8주 (인플루엔자는 현재 절기) | §3-2 UK | 포털 요청 간격 ≥ 3초, 실행당 요청 상한 (설정) |

- 주기는 전부 설정값(`batch.schedule.*-cron`)이다. 표본감시 공표 요일은 확인되지 않았다 — 2026-09-30(수) 기준 38주(09-13 ~ 09-19)까지 공개돼 있었다. 몇 주 적재해 보고 조정한다.
- 공식 API 가 없는 표본감시는 포털 화면이 쓰는 데이터 요청으로 받는다. **공개 API 가 아니므로** 형식이 바뀌면 조용히 틀리지 않고 실패하게 만든다 — 열 제목(`captionList`)을 설정의 기대 목록과 대조하고, 다르면 `SENTINEL_SCHEMA_CHANGED` 로 실패하고 기존 데이터는 그대로 둔다 ([data-api-analysis.md §3](data-api-analysis.md#3-표본감시-감염병포털)).
- 포털이 막히면 그 주는 적재하지 않고 FAILED 를 남긴다. 기존 값은 그대로 보이고, 다음 실행이 최근 8주를 다시 받으므로 한 주 빠져도 메워진다. 급하면 수동 재실행한다. **포털 CSV 수동 적재는 두지 않는다** — 헤더가 없고 합계 정의가 화면과 달라 기존 값을 망가뜨린다 ([data-api-analysis.md §3-2](data-api-analysis.md#3-2-1단계는-화면-데이터json를-쓴다)).

### 4-2. Quartz 규칙 (hondigagae batch-service 와 같다)

- 트리거는 코드(`QuartzScheduleConfig`)에 정의하고 cron 값만 설정으로 뺀다. Quartz cron 은 초가 맨 앞이다 (예: `0 0 6 ? * FRI`). 시간대는 `batch.schedule.time-zone=Asia/Seoul` 로 고정한다.
- 잡 스토어는 in-memory, 스레드 1개 — 잡끼리 겹치지 않는다. misfire 는 `FireAndProceed` 지만 **프로세스가 떠 있는 동안 늦어진 발화만** 보충한다. in-memory 스토어라 배포 · 장애로 내려가 있던 동안의 발화는 재기동 뒤 보충되지 않는다 → 배포는 발화 시각을 피하고, 놓쳤으면 수동 실행한다.
- 같은 잡은 `@DisallowConcurrentExecution`, 다른 잡 · 수동 실행 JVM 과의 겹침은 배치 메타데이터의 실행 중(STARTING · STARTED · STOPPING) 실행으로 판정해 이번 주기를 건너뛴다. 시작한 지 `batch.schedule.stale-running-after`(기본 6h)를 넘긴 실행은 죽은 JVM 의 잔재로 보고 무시한다 (ERROR 로그). 실행 브리지 구성은 [modules.md](modules.md#servicebatch-service).
- 스케줄 발화는 예정 발화 시각을 `batch.schedule.time-zone`(기본 KST) 기준 초 단위로 자른 `runAt`(예: `2026-10-06T05:00:00`)을 identifying 파라미터로, `trigger=quartz` 를 기록용(non-identifying)으로 넘긴다.
- 스케줄 스위치 `batch.schedule.enabled` 는 dev 만 true. 수동 실행은 `--spring.batch.job.name=<잡> runAt=<ISO 시각>` 로 한다 (`runAt` 을 새 값으로 주지 않으면 이미 완료된 JobInstance 로 거절된다). `districtImportJob` 은 `year=<기준 연도>` 가 필수이고, 대규모 폐지를 허용할 때만 `allowMassRetire=true` 를 더한다. `notifiableImportJob` 은 `runAt` 만 필수이고, 지난해를 다시 받을 때 `year=<올해로 볼 연도>`(2000 ~ `runAt` 의 연도)를 더한다 — 그 해와 전년을 받는다. 예: `runAt=2026-10-07T10:30:00 year=2025`. 같은 `runAt` 이 COMPLETED 면 거절되고, FAILED 면 같은 실행을 재시작한다(74건을 처음부터 다시 부르고 이력의 `run_started_at` 이 두 시도에 걸친다). 다시 받을 때는 새 `runAt`(현재 시각)을 준다.
- 적재는 JDBC `batchUpdate` + `ON DUPLICATE KEY UPDATE`, 500건 단위 (배치 대량 쓰기는 JDBC 허용 — [coding-conventions.md §8-4](coding-conventions.md#8-4-쿼리-수단-순서)).
- 원천 하나가 실패해도 다른 잡은 계속 돈다. 실패한 원천의 기존 데이터는 지우지 않는다.

### 4-3. Spring Batch 메타 테이블

`BATCH_*` 메타 테이블은 **surveillance 스키마**에 둔다 (hondigagae 가 `tour` 스키마에 두는 것과 같은 관계, 데이터소스 하나). batch-service 는 이미 surveillance 스키마에 쓰는 유일한 예외이므로 스키마를 하나 더 늘리지 않는다. 접속 계정은 surveillance-service 와 다른 **적재용 계정**(`BATCH_DB_USERNAME`)이다. dev 는 애플리케이션이 만들고(`initialize-schema: always`), prod 는 DB 담당자가 적용한다(기본 `never`). 구성 정본은 [modules.md](modules.md#servicebatch-service) (#25).

---

## 5. 운영 조회

운영자 화면 · 장애 확인에 필요한 조회와 받쳐 주는 인덱스.

| 조회 | 테이블 · 인덱스 |
|------|-----------------|
| 이번 주 동네별 집계 목록 (검토 후보 계산 포함) | `district_weekly_aggregate` UK `(district_code, iso_week)` + 전주 행 |
| 동네의 발행 안내 목록 | `idx_advisory_district_code_status_published_at` |
| 안내 한 건의 전 이력 | `idx_advisory_history_advisory_id` |
| 미완료 보고 파기 | `idx_report_purge_request_completed_at` (`completed_at is null`) |
| 원천별 마지막 적재 결과 | `idx_official_source_snapshot_request_key_created_at` 최신 행 |
| 공식 자료 최근 기간 | `idx_official_surveillance_program_period_start` |

---

## 6. 뒤로 미룬 테이블 (2단계)

1단계에서는 아래 기능을 테이블 없이 운영하거나 하지 않는다. 추가할 때 이 문서에 절을 더한다.

| 테이블 | 기능 | 1단계에서는 |
|--------|------|-------------|
| `push_subscription` · `notification_delivery` (auth) | PWA 푸시 구독 · 발송 로그 | 발행 안내는 서비스 안에서만 본다 |
| `outbox_event` (surveillance) | 발행 → 알림 전달 | 알림이 없으니 필요 없다 |
| `review_signal` (surveillance) | 검토 후보 적재 · 처리 상태 | 운영자 화면이 조회 시 계산한다 (§2-2) |
| `district_baseline` (surveillance) | 기준선 · 기준선 대비 변화 | 없음 |
| `threshold_config` (surveillance) | 판정 임계값 운영자 변경 | `application.yml` 설정 + `rule_version` |
| `member_role_history` (auth) | OPERATOR · ADMIN 역할 부여 · 회수 이력 | ADMIN 이 DB 로 직접 부여하고 로그로 남긴다 |

---

## 7. Enum 정의 목록

| Enum | 값 | 사용처 |
|------|-----|--------|
| `SecurityRole` | USER / OPERATOR / ADMIN | member |
| `OAuthProvider` | KAKAO | member |
| `MemberStatus` | ACTIVE / WITHDRAWN / SUSPENDED | member |
| `ConsentType` | TERMS_OF_SERVICE / PRIVACY_POLICY / SENSITIVE_HEALTH_INFO / AGE_OVER_19 | member_consent |
| `PurgeReason` | WITHDRAWAL / HEALTH_CONSENT_WITHDRAWN | report_purge_request |
| `SymptomGroup` (비트) | RESPIRATORY(1, 발열 · 기침 · 인후통) / ENTERIC(2, 구토 · 설사) | weekly_report.symptom_mask, 보고 요청 |
| `AggregateLevel` | GOOD(좋음) / NORMAL(보통) / CAUTION(주의) / INSUFFICIENT(판단 보류 — 화면 문구는 `자료 부족`) | district_weekly_aggregate, advisory |
| `InsufficientReason` | LOW_SAMPLE / UNSTABLE | district_weekly_aggregate |
| `AdvisoryStatus` | DRAFT / AI_DRAFTED / EDITED / APPROVED / PUBLISHED / RETRACTED | advisory, advisory_history |
| `OfficialSource` | KDCA_NOTIFIABLE / KDCA_SENTINEL | official_surveillance, official_source_snapshot |
| `OfficialProgram` | NOTIFIABLE / INFLUENZA_ILI / ARI / ENTERIC | official_surveillance, official_source_snapshot |
| `OfficialMetric` | CASE_COUNT / INCIDENCE_PER_100K / ILI_PER_1000 | official_surveillance |
| `OfficialAgeGroup` | ALL / AGE_0 / AGE_1_6 / AGE_7_12 / AGE_13_18 / AGE_19_49 / AGE_50_64 / AGE_65_PLUS | official_surveillance |
| `OfficialRegionLevel` | NATION / SIDO | official_surveillance |
| `OfficialPeriodType` | WEEK / YEAR | official_surveillance |
| `IngestChannel` | OPEN_API / PORTAL_JSON | official_source_snapshot |
| `IngestStatus` | IMPORTED / FAILED | official_source_snapshot |

- **비트 값은 한 번 정하면 바꾸지 않는다** (저장된 데이터가 깨진다). 증상군을 늘릴 때는 다음 비트(4)를 쓴다.
- 전부 [coding-conventions.md §7](coding-conventions.md#7-enum-metadata) metadata 규칙을 따른다.

---

## 8. 보관 기간

| 테이블 | 보관 | 끝나면 |
|--------|------|--------|
| member | 탈퇴 후 30일, **보고 파기 완료 후** | 파기 스케줄러가 hard delete |
| member_consent · member_region | 회원과 같다 | 회원 파기 때 함께 삭제 |
| report_purge_request | 완료 후 1년 (설정) — 파기 증빙 | 정리 스케줄러가 삭제 |
| weekly_report | 52주 · 탈퇴 / 철회 시 즉시 | 삭제 스케줄러 · 파기 API |
| district_weekly_aggregate | 계속 (익명 집계) | — |
| advisory · advisory_history | 계속 (발행 · 승인 증빙) | — |
| district | 계속 (폐지 코드도 과거 행이 참조) | — |
| official_surveillance | 계속 | — |
| official_source_snapshot | 계속 (`official_surveillance.source_snapshot_id` 가 참조한다. 실행당 수십 행 · 연 수천 행이라 작다) | — |
| Redis (인증 코드 · 세션 · 블랙리스트) | 각 TTL | 자동 만료 |
