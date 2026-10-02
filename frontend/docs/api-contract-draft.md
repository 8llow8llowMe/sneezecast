# API 계약

백엔드(origin/develop)와 맞춘 계약이다. **인증 · 회원 · 행정동은 백엔드에 구현돼 있어 확정**이고, 보고 · 집계 · 안내 · 공식 정보 · 운영자는 **BE 미정**이라 아래 초안은 프론트 제안이다.

- 계약의 정본은 백엔드 컨트롤러의 `@Operation` 설명과 [`backend/docs/modules.md`](../../backend/docs/modules.md) 다. **Swagger 는 공개 도메인(`https://api-dev.sneezecast.com`)에서 볼 수 없다** — 게이트웨이가 Swagger 경로를 라우팅하지 않고 문서 집계도 두지 않는다. 계약이 바뀌면 백엔드 코드 · 문서와 대조해 이 문서를 고친다.
- 호출 방법(래퍼 · 토큰 · 오류 처리)은 [conventions.md](conventions.md) "API 계층" 이 정본이다.
- 백엔드 설계 문서: [`backend/docs/architecture-guide.md`](../../backend/docs/architecture-guide.md), [`backend/docs/entity-design.md`](../../backend/docs/entity-design.md)

## 공통 (확정)

- 주소: 게이트웨이 `https://api-dev.sneezecast.com`(dev) · `https://api.sneezecast.com`(prod) + `/api/v1/...`. 프론트는 `clientEnv.apiBaseUrl` 로 읽는다. 게이트웨이 라우트는 `/api/v1/auth/**` · `/api/v1/members/**`(auth) · `/api/v1/districts/**` · `/api/v1/reports/**` · `/api/v1/advisories/**`(surveillance) 이다. `/internal/**` 은 라우팅하지 않는다.
- CORS: 웹 도메인 두 개 + `http://localhost:*`, `allowCredentials=true`.
- 응답 봉투 (모든 응답, 게이트웨이 오류 포함):

  ```json
  {
    "dataHeader": {
      "success": false,
      "resultCode": "MEMBER_102",
      "resultMessage": "대표 오류 문구 (늘 문자열)",
      "fieldErrors": [{ "code": "MEMBER_102", "field": "nickname", "message": "…" }]
    },
    "dataBody": null
  }
  ```

  성공이면 `resultCode` · `resultMessage` · `fieldErrors` 가 null 이고 `dataBody` 에 본문이 있다(본문 없는 API 는 null). 실패면 `dataBody` 가 null 이다. `fieldErrors` 는 검증 실패일 때만 있고, 한 필드에 여러 개면 서버가 순서를 고정한다(`resultCode` 는 첫 오류). 목록 API 는 `SliceResponse { contents, hasNext }` 가 기본이다(행정동 검색은 예외로 배열).

- **봉투가 없는 응답은 일시 장애다.** 게이트웨이의 JWT 거부 · 자체 오류는 봉투지만 그 밖의 게이트웨이 오류 · 프록시 오류는 Spring 기본 형식 · HTML 이다. 프론트는 이것과 네트워크 실패 · 타임아웃을 `UNAVAILABLE` 로 다룬다.
- 업스트림 상한: 게이트웨이가 10초 넘게 기다리면 `GATEWAY_004`(504)로 끊는다. 프론트 타임아웃은 12초다.

### 공통 오류 코드

| 코드                                           | 상태 | 뜻                                                                         | 프론트 갈래(`classifyApiError`) |
| ---------------------------------------------- | ---- | -------------------------------------------------------------------------- | ------------------------------- |
| `SECURITY_001`                                 | 401  | 인증 필요 (토큰 없이 회원 API) — 서비스가 낸다                             | `login-required`                |
| `SECURITY_002` · `003` · `004` · `005` · `007` | 401  | access 만료 · 무효 · 서명 · 형식 · 폐기(로그아웃) — 게이트웨이도 같은 코드 | `reissue`                       |
| `SECURITY_006`                                 | 403  | 권한 없음 — 서비스가 낸다                                                  | `forbidden`                     |
| `SECURITY_008`                                 | 503  | 토큰 검증 저장소 장애                                                      | `unavailable`                   |
| `GATEWAY_001`                                  | 400  | 허용되지 않는 경로                                                         | `other`                         |
| `GATEWAY_002`                                  | 404  | 라우트 없음                                                                | `other`                         |
| `GATEWAY_003`                                  | 503  | 서비스 인스턴스 없음 · 연결 실패                                           | `unavailable`                   |
| `GATEWAY_004`                                  | 504  | 업스트림 지연                                                              | `unavailable`                   |
| `{DOMAIN}_100` · `_198` · `_199`               | 400  | 요청 본문 · 파라미터 형식 · 필수 파라미터 누락                             | `other`                         |

## 인증 `/api/v1/auth` (확정)

refresh 토큰은 본문이 아니라 쿠키 `refreshToken`(HttpOnly · Secure · SameSite=Strict · `Path=/api/v1/auth`)으로만 오간다. 프론트는 모든 요청에 `credentials: 'include'` 를 싣는다. **FE 로컬(`http://localhost`)에서는 교차 사이트라 쿠키가 실리지 않는다** — 재발급 · 로그아웃 쿠키 흐름은 dev 웹에서 확인한다.

| 요청                               | 인증                                  | 바디 → `dataBody`                                                                                                                            | 주요 오류                                                                                                     | 프론트 함수(`auth-client.ts`) |
| ---------------------------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| `POST /email/send-code`            | 불필요                                | `{ email }` → null (가입 여부와 무관하게 같음)                                                                                               | `AUTH_001` 쿨다운 · `002` IP 상한(429), `006`(503)                                                            | `sendEmailCode`               |
| `POST /email/verify-code`          | 불필요                                | `{ email, code }` → null (인증 표시 기본 30분)                                                                                               | `AUTH_003` 불일치 · `004` 만료 · 없음 · `005` 시도 초과(400), `010` IP 상한(429)                              | `verifyEmailCode`             |
| `POST /signup`                     | 불필요                                | `{ email, password, nickname, termsAgreed, privacyAgreed, ageOver19Confirmed, sensitiveHealthInfoAgreed }` → null (토큰 없음, 이어서 로그인) | `AUTH_007` 미인증, `MEMBER_001` 가입된 이메일(409), 검증 `AUTH_110` · `111` · `112`(필수 체크)                | `signup`                      |
| `POST /login`                      | 불필요                                | `{ email, password }` → `AuthToken`                                                                                                          | `AUTH_011`(401) · `012` 잠금 · `013` IP 상한(429), `MEMBER_002` 탈퇴 · `003` 정지(403), `AUTH_017`(503)       | `loginWithEmail`              |
| `POST /token/reissue`              | **Authorization 없이** (refresh 쿠키) | 바디 없음 → `AuthToken` (refresh 회전)                                                                                                       | `AUTH_014` · `015` 재로그인(401), `016` 경합(409, 한 번 다시), `MEMBER_002` · `003`(403), `AUTH_017`(503)     | (세션 저장소, 연동 이슈)      |
| `POST /logout`                     | 필요                                  | 바디 없음 → null (이 기기 세션 폐기 · 쿠키 지움)                                                                                             | `SECURITY_001` · `002`                                                                                        | `logout`                      |
| `GET /sessions`                    | 필요                                  | → `{ sessions: [{ sessionId, deviceLabel, createdAt, lastUsedAt, current }], totalCount }` (시각은 ISO-8601 UTC)                             |                                                                                                               | `listSessions`                |
| `DELETE /sessions/{sessionId}`     | 필요                                  | → null (멱등, 지금 기기면 쿠키도 지움)                                                                                                       | `AUTH_114` UUID 형식(400)                                                                                     | `revokeSession`               |
| `DELETE /sessions`                 | 필요                                  | → null (지금 기기 빼고 모두)                                                                                                                 | `AUTH_014`(401)                                                                                               | `revokeOtherSessions`         |
| `POST /password/reset/send-code`   | 불필요                                | `{ email }` → null (가입 여부와 무관하게 같음)                                                                                               | `AUTH_001` · `002`(429)                                                                                       | `sendPasswordResetCode`       |
| `POST /password/reset/verify-code` | 불필요                                | `{ email, code }` → `{ resetToken }` (1회용 · 기본 15분)                                                                                     | `AUTH_003` · `004` · `005`(400), `010`(429)                                                                   | `verifyPasswordResetCode`     |
| `POST /password/reset`             | 불필요                                | `{ resetToken, newPassword }` → null (모든 기기 로그아웃)                                                                                    | `AUTH_018` 인증 만료(400, 코드부터 다시), `019` IP 상한(429), `017`(503), 검증 `AUTH_105~107` · `115` · `116` | `resetPassword`               |

- `AuthToken` = `{ memberId, role(USER | OPERATOR | ADMIN), accessToken, accessTokenExpiresIn(초), pendingConsents: string[], reportWritable }`. access token 은 메모리에만 두고 만료 전에 재발급한다. `pendingConsents` 가 비어 있지 않으면 약관 재동의 화면으로, `reportWritable` 이 false 면 보고 진입에서 건강정보 동의 시트를 연다.
- 재발급 요청에 `Authorization` 을 실으면 게이트웨이가 만료된 access 를 검사해 refresh 가 멀쩡해도 `SECURITY_002` 로 끝난다.
- 비밀번호 규칙: 8~20자 · 영문자와 숫자 포함 · 공백 금지(특수문자 선택). 로그인 비밀번호는 100자 상한만 본다(`AUTH_113`). 닉네임 2~10자.
- 재설정 토큰(`resetToken`)은 메모리에만 들고 주소 · 로그 · 브라우저 저장소에 남기지 않는다. 발송 제한은 남은 시간을 주지 않는다 — 문구에 시간을 못 박지 않는다.
- **BE 미정**: 카카오 로그인(`/kakao/*`, 백엔드 #61), 건강정보 동의 · 철회 · 약관 재동의 · 탈퇴(#59). 프론트 `startKakaoLogin` · `agreeHealthConsent` · `withdrawHealthConsent` · `agreeTermsReconsent` · `withdrawMembership` 은 목이다.

## 회원 `/api/v1/members` (확정)

모두 인증 필요(`Authorization: Bearer`).

| 요청                      | 바디 → `dataBody`                                              | 주요 오류                                                                                          | 프론트 함수      |
| ------------------------- | -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | ---------------- |
| `GET /me`                 | → `MyInfo`                                                     | `MEMBER_004` 회원 없음(404, 로그아웃 상태로), `002` · `003`(403)                                   | (세션 훅)        |
| `PATCH /me`               | `{ nickname }` → `MyInfo`                                      | 검증 `MEMBER_101` · `102`                                                                          | (연동 이슈)      |
| `POST /me/password`       | `{ currentPassword, newPassword }` → null (다른 기기 로그아웃) | `MEMBER_005` 불일치(400) · `006` 잠금(429) · `007` 비밀번호 없음(409) · `009`(503), 검증 `103~107` | `changePassword` |
| `POST /me/password/setup` | `{ newPassword }` → null (다른 기기 로그아웃)                  | `MEMBER_008` 이미 있음(409) · `009`(503), 검증 `105~107`                                           | `setupPassword`  |

- `MyInfo` = `{ memberId, email, nickname, provider(EMAIL | KAKAO), hasPassword, role, pendingConsents, reportWritable }`. `hasPassword` 가 false 면 비밀번호 설정, true 면 비밀번호 변경을 보인다.
- **BE 미정**: 내 동네(`saveRegion`, 백엔드 #60) · 프로필 이미지(#112) · 동네 폐지 표시. `GET /me` 는 아직 내 동네를 싣지 않는다.

## 행정동 `/api/v1/districts` (확정)

인증 불필요(비로그인 둘러보기에서도 쓴다).

| 요청              | → `dataBody`                                                         | 주요 오류                                                  | 프론트 함수(`region-client.ts`) |
| ----------------- | -------------------------------------------------------------------- | ---------------------------------------------------------- | ------------------------------- |
| `GET ?query=역삼` | `[{ code, name, sigungu }]` (현행 동만, 코드 오름차순, 최대 20건)    | `DISTRICT_101` 검색어 없음 · `102` 20자 초과(400)          | `searchDistricts`               |
| `GET /{code}`     | `{ code, name, sigungu, active }` (폐지 코드도 200 · `active=false`) | `DISTRICT_001` 없는 코드(404), `103` 형식(숫자 8자리, 400) | `findDistrict`                  |

- `code` 는 SGIS 행정동 코드 8자리, `sigungu` 는 `서울특별시 강남구` 처럼 시도 · 시군구 표기다. 검색어는 앞뒤 공백을 뺀 1~20자이고 `%` · `_` 도 글자 그대로 찾는다. 일치하는 동이 없으면 빈 배열이다.
- **프론트 연동 (#135)**: 두 API 모두 `region-client.ts` 가 데이터 출처(`api` | `mock`, [conventions.md](conventions.md) "데이터 출처")를 받아 부른다. 공개 API 라 `auth: false` 로 Authorization 없이 보낸다(만료된 access 로 게이트웨이가 거절하지 않게).
  - `searchDistricts(query, source, signal?)`: 앞뒤 공백을 뺀 검색어로 부른다. 빈 검색어 · 20자 초과는 요청 없이 빈 배열이다(`DISTRICT_101` · `102` 를 받지 않는다). 검색 화면(`useDistrictSearch`)은 검색어가 바뀌면 앞 요청을 취소하고, 실패하면 다시 검색하라고 안내한다. 검색 입력칸에는 아직 `maxLength` 가 없다.
  - `findDistrict(code, source)`: 숫자 8자리가 아니면 요청 없이 null(`DISTRICT_103` 을 받지 않는다), 404(`DISTRICT_001`)면 null, 그 밖의 실패는 거부한다. 돌려주는 값은 `{ code, name, sigungu, active }` 다(목은 늘 `active: true`).
  - 둘러보기 동네(`?region=`, `districtFromParam`)는 폐지 코드 · API 실패를 모르는 동네(null)로 보고 원래 동네로 그린다. 동네 안내(`getRegionNotice`)는 폐지 코드면 404, 동네 확인이 실패하면 페이지 오류 경계로 보낸다.
  - 내 동네가 폐지됐을 때 다시 고르게 하는 흐름(Setup-1-reselect)은 내 동네 API(#60) 연동 때 `active=false` 로 잇는다.
- **BE 미정**: 폐지된 동의 후속 후보(`listSuccessorDistricts`) — 출처와 무관하게 목이다.

## BE 미정 — 프론트 초안

백엔드에 아직 없다(게이트웨이 라우트 `/api/v1/reports/**` · `/api/v1/advisories/**` 만 열려 있다). 아래는 화면을 만들며 가정한 모양이고, 연동 이슈에서 백엔드와 맞춘다. 지역 키 `admCd` 는 행정동 `code`(SGIS 8자리)다.

```text
GET  /api/regions/{admCd}/weekly?week=     → { status: normal|slight|high|insufficient,
                                                participants, publicThreshold,
                                                symptomRate?, baselineRate?,   // insufficient면 없음
                                                groups: [{ key, trend, series: number[] }] }
POST /api/reports                          → { admCd, week, answer: none|symptom, groups: [] }
PUT  /api/reports/{week}                   → 같은 주 수정
DELETE /api/reports/{week}                 → 되돌리기(직후)·삭제
GET  /api/notices/{admCd}?week=            → 발행된 안내 | null (정정·철회 이력 포함)
GET  /api/official/latest                  → 질병관리청 단계·기준 주·요약·원문 링크
GET  /api/admin/candidates?week=           → 후보 목록 (운영자)
POST /api/admin/candidates/{id}/publish|hold|correct|withdraw
```

- 로그인한 회원만 보고한다. 보고 데이터에는 회원 식별자 대신 가명 키(`reporter_key`)만 남는다.
- `insufficient` 응답에는 `symptomRate` · `baselineRate` 가 없다. 프론트는 이 값이 없을 때 수치·상태색을 그리지 않는다.
- 프론트 함수: 보고 `report-client.ts`(`submitReport` · `updateReport` · `cancelReport`), 안내 `notice-client.ts`(`getRegionNotice`). 홈 주간 집계 · 공식 정보 · 운영자는 목 데이터다.
