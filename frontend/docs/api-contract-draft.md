# API 계약

백엔드(origin/develop)와 맞춘 계약이다. **인증 · 회원(관심 동네 · 알림 설정 포함) · 행정동 · 주간 보고(이번 주 · 지난 보고 내역) · 행정동 주간 집계(홈 · 지도) · 공식 정보 · 동네 안내 · 운영자 안내 처리(초안 · 수정 · 승인 · 발행 · 철회 · 상세) · 운영자 검토 후보 · 운영자 정정 · 발행 이력은 백엔드에 구현돼 있어 확정**이다. 절 안에 **BE 미정** 으로 적은 항목만 백엔드에 아직 없다.

- 계약의 정본은 백엔드 컨트롤러의 `@Operation` 설명과 [`backend/docs/modules.md`](../../backend/docs/modules.md) 다. **Swagger 는 공개 도메인(`https://api-dev.sneezecast.com`)에서 볼 수 없다** — 게이트웨이가 Swagger 경로를 라우팅하지 않고 문서 집계도 두지 않는다. 계약이 바뀌면 백엔드 코드 · 문서와 대조해 이 문서를 고친다.
- 호출 방법(래퍼 · 토큰 · 오류 처리)은 [conventions.md](conventions.md) "API 계층" 이 정본이다.
- 백엔드 설계 문서: [`backend/docs/architecture-guide.md`](../../backend/docs/architecture-guide.md), [`backend/docs/entity-design.md`](../../backend/docs/entity-design.md)

## 공통 (확정)

- 주소: 게이트웨이 `https://api-dev.sneezecast.com`(dev) · `https://api.sneezecast.com`(prod) + `/api/v1/...`. 프론트는 `clientEnv.apiBaseUrl` 로 읽는다. 게이트웨이 라우트는 `/api/v1/auth/**` · `/api/v1/members/**`(auth) · `/api/v1/districts/**` · `/api/v1/reports/**` · `/api/v1/advisories/**` · `/api/v1/official/**`(surveillance) 이다. `/internal/**` 은 라우팅하지 않는다.
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

| 요청                                  | 인증                                  | 바디 → `dataBody`                                                                                                                                                                  | 주요 오류                                                                                                                                                 | 프론트 함수(`auth-client.ts`)         |
| ------------------------------------- | ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| `POST /email/send-code`               | 불필요                                | `{ email }` → null (가입 여부와 무관하게 같음)                                                                                                                                     | `AUTH_001` 쿨다운 · `002` IP 상한(429), `006`(503)                                                                                                        | `sendEmailCode`                       |
| `POST /email/verify-code`             | 불필요                                | `{ email, code }` → null (인증 표시 기본 30분)                                                                                                                                     | `AUTH_003` 불일치 · `004` 만료 · 없음 · `005` 시도 초과(400), `010` IP 상한(429)                                                                          | `verifyEmailCode`                     |
| `POST /signup`                        | 불필요                                | `{ email, password, nickname, termsAgreed, privacyAgreed, ageOver19Confirmed, sensitiveHealthInfoAgreed }` → null (토큰 없음, 이어서 로그인)                                       | `AUTH_007` 미인증, `MEMBER_001` 가입된 이메일(409), 검증 `AUTH_110` · `111` · `112`(필수 체크)                                                            | `signup`                              |
| `POST /login`                         | 불필요                                | `{ email, password }` → `AuthToken`                                                                                                                                                | `AUTH_011`(401) · `012` 잠금 · `013` IP 상한(429), `MEMBER_002` 탈퇴 · `003` 정지(403), `AUTH_017`(503)                                                   | `loginWithEmail`                      |
| `POST /token/reissue`                 | **Authorization 없이** (refresh 쿠키) | 바디 없음 → `AuthToken` (refresh 회전)                                                                                                                                             | `AUTH_014` · `015` 재로그인(401), `016` 경합(409, 한 번 다시), `MEMBER_002` · `003`(403), `AUTH_017`(503)                                                 | (`session-store.ts` 재발급)           |
| `POST /logout`                        | 필요                                  | 바디 없음 → null (이 기기 세션 폐기 · 쿠키 지움)                                                                                                                                   | `SECURITY_001` · `002`                                                                                                                                    | `logout`                              |
| `GET /sessions`                       | 필요                                  | → `{ sessions: [{ sessionId, deviceLabel, createdAt, lastUsedAt, current }], totalCount }` (시각은 ISO-8601 UTC)                                                                   |                                                                                                                                                           | `listSessions`                        |
| `DELETE /sessions/{sessionId}`        | 필요                                  | → null (멱등, 지금 기기면 쿠키도 지움)                                                                                                                                             | `AUTH_114` UUID 형식(400)                                                                                                                                 | `revokeSession`                       |
| `DELETE /sessions`                    | 필요                                  | → null (지금 기기 빼고 모두)                                                                                                                                                       | `AUTH_014`(401)                                                                                                                                           | `revokeOtherSessions`                 |
| `POST /password/reset/send-code`      | 불필요                                | `{ email }` → null (가입 여부와 무관하게 같음)                                                                                                                                     | `AUTH_001` · `002`(429)                                                                                                                                   | `sendPasswordResetCode`               |
| `POST /password/reset/verify-code`    | 불필요                                | `{ email, code }` → `{ resetToken }` (1회용 · 기본 15분)                                                                                                                           | `AUTH_003` · `004` · `005`(400), `010`(429)                                                                                                               | `verifyPasswordResetCode`             |
| `POST /password/reset`                | 불필요                                | `{ resetToken, newPassword }` → null (모든 기기 로그아웃)                                                                                                                          | `AUTH_018` 인증 만료(400, 코드부터 다시), `019` IP 상한(429), `017`(503), 검증 `AUTH_105~107` · `115` · `116`                                             | `resetPassword`                       |
| `GET /kakao/authorize?switchAccount=` | 불필요 (state 쿠키를 받음)            | → `{ authorizeUrl }` (카카오 인가 주소. 받다 만 가입표 · 연결 확인표 쿠키는 지움)                                                                                                  | `AUTH_028` IP 상한(429)                                                                                                                                   | `startKakaoLogin`(`kakao-client.ts`)  |
| `POST /kakao/login`                   | 불필요 (state 쿠키)                   | `{ code, state }` → `{ result, … }` — `LOGGED_IN`(+ `AuthToken` 필드) · `SIGNUP_REQUIRED`(+ `nickname`, 가입표 쿠키 30분) · `LINK_REQUIRED`(+ 가린 `email`, 연결 확인표 쿠키 10분) | `AUTH_020` state 무효 · `021` code 거부 · `023` 이메일 미제공 · `024` 미인증(400), `022` 카카오 장애(503), `MEMBER_002` · `003`(403), 검증 `AUTH_117~120` | `kakaoLogin`(`kakao-client.ts`)       |
| `POST /kakao/signup`                  | 불필요 (가입표 쿠키)                  | `{ termsAgreed, privacyAgreed, ageOver19Confirmed }` → `AuthToken` (가입하고 바로 로그인)                                                                                          | `AUTH_025` 가입표 만료(400), `MEMBER_001`(409), 검증 `AUTH_110~112`                                                                                       | `signup`(kind kakao)                  |
| `POST /kakao/link`                    | 불필요 (연결 확인표 쿠키)             | 바디 없음 → `AuthToken` (이메일 계정에 카카오 로그인 연결, 비밀번호는 그대로)                                                                                                      | `AUTH_026` 확인표 만료(400), `027` 연결할 수 없는 계정(409)                                                                                               | `linkKakaoAccount`(`kakao-client.ts`) |

- `AuthToken` = `{ memberId, role(USER | OPERATOR | ADMIN), accessToken, accessTokenExpiresIn(초), pendingConsents: string[], reportWritable }`. access token 은 메모리에만 두고 만료 전에 재발급한다. `pendingConsents` 가 비어 있지 않으면 약관 재동의 화면으로, `reportWritable` 이 false 면 보고 진입에서 건강정보 동의 시트를 연다.
- 재발급 요청에 `Authorization` 을 실으면 게이트웨이가 만료된 access 를 검사해 refresh 가 멀쩡해도 `SECURITY_002` 로 끝난다.
- 비밀번호 규칙: 8~20자 · 영문자와 숫자 포함 · 공백 금지(특수문자 선택). 로그인 비밀번호는 100자 상한만 본다(`AUTH_113`). 닉네임 2~10자.
- 재설정 토큰(`resetToken`)은 메모리에만 들고 주소 · 로그 · 브라우저 저장소에 남기지 않는다. 발송 제한은 남은 시간을 주지 않는다 — 문구에 시간을 못 박지 않는다.
- **프론트 연동 (#163)**: `sendEmailCode` · `verifyEmailCode` · `signup`(이메일 가입) · `loginWithEmail` · `logout` 이 데이터 출처(`api` | `mock`, [conventions.md](conventions.md) "데이터 출처")를 마지막 인자로 받아 부른다. 부르는 화면이 `useDataSource()` 로 넘긴다.
  - 인증이 필요 없는 send-code · verify-code · signup · login 은 `auth: false` 로 Authorization 없이 보낸다(만료된 access 로 게이트웨이가 `SECURITY_002` 로 거절하지 않게). logout 만 access 를 싣는다.
  - 오류 매핑: send-code `AUTH_001` · `002` → `limit`. verify-code `AUTH_003` → `wrong`(남은 시도는 서버가 주지 않아 `auth-client.ts` 가 이메일별 실패 수를 메모리에 세어 채운다 — 코드를 받거나 · 인증을 마치거나 · `004` · `005` 면 0 으로), `AUTH_004` → `expired`, `AUTH_005` · `010` → `locked`. signup `AUTH_007` → `verification-expired`, `MEMBER_001` → `email-taken`(S02-3 에서 이메일 로그인으로 안내). login `AUTH_011` · `MEMBER_002` · 검증 `AUTH_102` · `103` · `113` → `wrong`, `AUTH_012` → `locked`, `AUTH_013` → `limited`, `MEMBER_003` → `suspended`. 그 밖(503 `AUTH_006` · `017` · 일시 장애 `UNAVAILABLE` · 분류 밖 검증 오류)은 거부하고, 화면은 "잠시 뒤 다시 시도" 안내를 띄운다.
  - 로그인 성공 → 응답(`AuthToken`)을 그대로 `setSession`. 가입 응답에는 토큰이 없어 S02-3 이 가입 → 로그인 → 동네 저장 순서로 잇고 비밀번호는 로그인 뒤 지운다. 가입 본문의 동의 값은 화면의 동의 목록(`legal.ts` `Consent`)에서 만들고 문서 버전은 보내지 않는다(서버의 `legal.*-version`). `sensitiveHealthInfoAgreed` 는 S02-4 에서 따로 받으므로 false 다.
  - **로그아웃 실패 정책**: 성공 · 토큰이 이미 무효(`login-required` · `reissue` · `relogin` — API 계층의 재발급이 재로그인으로 끝나 세션을 이미 비운 경우 포함)면 `clearSession('logout')`. 일시 장애 · 분류 밖 오류면 거부하고 세션을 그대로 둔다 — 서버의 refresh 세션이 살아 있는데 화면만 로그아웃된 것처럼 보이지 않게 한다. 화면(내 정보 · 재동의)은 로그아웃 실패 안내를 띄운다.
  - 세션이 사라진 갈래는 세션 저장소가 아직 회원일 때만 비운다(재발급이 재로그인으로 끝나 `clearSession('expired')` 로 이미 비웠으면 로그아웃을 다시 방송하지 않는다). 성공 · 세션 사라짐 모두 만료 진행 표시(`clearSessionExpiring`)를 꺼 화면의 홈 이동이 이기고 만료 토스트가 남지 않게 한다.
  - 탈퇴(`withdrawMembership`)는 아래 "회원" 의 `POST /me/withdraw`(백엔드 #154, 프론트 연동 #169)다. 건강정보 동의 · 철회 · 약관 재동의는 아래 "회원" 의 "프론트 연동 (#168)", 내 정보 · 내 동네는 아래 "회원" 의 "프론트 연동 (#164)", 비밀번호 재설정 · 로그인한 기기는 바로 아래 "프론트 연동 (#166)", 카카오는 아래 "프론트 연동 (#167)" 이다.
- **프론트 연동 (#166)**: `sendPasswordResetCode` · `verifyPasswordResetCode` · `resetPassword` · `listSessions` · `revokeSession` · `revokeOtherSessions` 도 출처를 마지막 인자로 받는다. 부르는 화면(재설정 세 단계 · 로그인한 기기)이 `useDataSource()` 로 넘긴다.
  - 재설정 세 요청은 `auth: false`, 기기 세 요청은 access 를 싣는다.
  - 재설정 코드 받기 · 확인은 가입 인증과 같은 갈래다: send-code `AUTH_001` · `002` → `limit`, verify-code `AUTH_003` → `wrong`(남은 시도는 이메일별 실패 수로 채움) · `004` → `expired` · `005` · `010` → `locked`. **실패 수는 가입과 따로 센다**(서버 Redis 키도 따로). 성공 응답의 `resetToken` 은 화면 Provider 메모리에만 두고, 토큰이 없는 성공 응답은 거부한다.
  - 재설정 `AUTH_018` · 검증 `AUTH_115` · `116`(토큰) → `verification-expired`(`/password/reset?reason=verification-expired`), `AUTH_019` → `limited`(토큰은 그대로라 잠시 뒤 다시 누름 — "요청이 많아 잠시 막혔어요"), 검증 `AUTH_105~107` → `invalid-password`(새 비밀번호 칸 아래 규칙 문구). `AUTH_017` · 일시 장애는 거부("바꾸지 못했어요") — `AUTH_017` 은 토큰이 이미 소비돼 다시 누르면 `AUTH_018` 로 이메일 단계부터 다시 한다.
  - **재설정 성공 → 서버가 그 계정의 모든 기기를 로그아웃한다.** 화면이 재설정한 이메일을 `resetPassword(token, newPassword, email, source)` 에 넘기고(메모리에서만 — 서버에는 보내지 않고 주소 · 로그에 남기지 않는다), 이 탭이 회원이면 회원 정보 저장소의 `MyInfo.email` 과 서버와 같은 정규화(앞뒤 공백 제거 · 소문자)로 비교한다.
    - 같으면 `clearSession('logout')`(사용자가 고른 결과라 만료 안내 없이 화면이 이메일 로그인으로 가고, 다른 탭에도 알린다).
    - 다르면 이 탭 세션을 건드리지 않는다 — 서버는 다른 계정의 세션만 끊었다. 회원인 채 `/login/email?reason=reset-done` 에 닿고, 첫 진입 가드는 이 화면을 보내지 않아 재설정 완료 안내가 그대로 보인다(거기서 로그인하면 그 계정으로 바뀐다).
    - 이메일을 모르면(내 정보를 읽는 중 · 실패) `logout('api')` 로 서버 세션까지 끊는다. 로그인한 회원이 오는 길은 내 정보의 "비밀번호를 잊었어요" 라 같은 계정일 가능성이 높고, 그러면 서버 세션은 이미 끊겼다. 로그아웃이 거부돼도(일시 장애) 이 탭은 비운다.
    - 목 재설정은 목 프로필 이메일이 토큰을 받은 이메일과 같을 때만 목 세션을 비운다.
  - 기기 목록은 `{ sessionId, deviceLabel, lastUsedAt, current }` 만 화면 모델(`DeviceSession { id, deviceName, lastActiveAt, current }`)로 옮긴다(`createdAt` · `totalCount` · 계약 밖 값은 버린다). 시각은 UTC 그대로 두고 화면이 한국 시각 라벨로 쓴다(`formatMonthDayTime`, `Intl` `Asia/Seoul`). **IP · 지역 · 위치는 받지도 그리지도 않는다.** 예시 기기 목록(`EXAMPLE_DEVICE_SESSIONS`)은 목데이터에서만 보인다.
  - `revokeSession({ id, current }, source)`: `DELETE /sessions/{id}`(id 는 `encodeURIComponent`). `current` 면 서버가 이 기기 쿠키까지 지우므로 `clearSession('logout')`(화면은 이 기기에 버튼을 두지 않는다). `AUTH_114` · 일시 장애는 거부("로그아웃하지 못했어요").
  - `revokeOtherSessions`: `AUTH_014`(서버가 이 토큰의 세션을 모름 — 이 기기가 이미 로그아웃됨)면 `clearSession('expired')`(로그인 만료 안내) 뒤 거부, 그 밖의 실패는 거부.
- **프론트 연동 (#167)** — 카카오 로그인 · 가입 · 계정 연결(백엔드 #61). `startKakaoLogin` · `kakaoLogin` · `linkKakaoAccount`(`features/auth/kakao-client.ts`)와 `signup` 카카오 갈래(`auth-client.ts`)가 출처를 받는다(`startKakaoLogin(source, { switchAccount })` · `kakaoLogin(code, state, source)` · `linkKakaoAccount(source)`). 네 요청 모두 `auth: false` 이고, state · 가입표 · 연결 확인표는 HttpOnly 쿠키(`Path=/api/v1/auth` · SameSite=Strict)라 화면이 다루지 않는다 — API 계층의 `credentials: 'include'` 로 오간다.
  - **진입**: 인가 주소는 `https://kauth.kakao.com/oauth/authorize`(백엔드 `oauth.kakao.authorize-uri`)와 오리진 · 경로가 같고 사용자 정보(`user:pass@`)가 없을 때만 따른다(`isKakaoAuthorizeUrl` — 오픈 리다이렉트 방지). 아니면 거부한다. 문서는 `src/lib/location.ts` 의 `assignLocation` 으로 옮긴다. 인가 코드는 요청 본문으로만 보내 API 로그에는 남지 않는다 — 카카오가 붙여 오는 콜백 문서 요청 자체의 쿼리는 웹 앞단 접근 로그 형식(Infra)에 달렸다. 콜백 문서에는 `Referrer-Policy: no-referrer`(`next.config.ts` `headers()`)를 붙인다. `AUTH_028` → `limited`("요청이 많아 잠시 막혔어요"), 그 밖은 거부("카카오 로그인을 시작하지 못했어요").
  - **콜백**(`/login/kakao/callback`, 백엔드 `KAKAO_REDIRECT_URI`): `KAKAO_REDIRECT_URI` 는 그대로이고 카카오는 지금처럼 쿼리(`?code=…&state=…` · `?error=…`)를 붙여 돌아온다. 프론트 proxy 가 그 쿼리를 fragment 로 옮겨 같은 경로로 303 리다이렉트하고(#176 — 동적 렌더링이 된 문서 HTML 에 인가 코드가 실리지 않게, `features/auth/kakao-callback-redirect.ts`), 화면은 hash 를 먼저 읽는다(없으면 쿼리). 백엔드가 바꿀 것은 없다. `code` · `state` 를 읽자마자 `history.replaceState` 로 주소에서 지운 뒤 `POST /kakao/login` 을 한 번만 보낸다. 실데이터면 새로고침 복원(`restoreSession` — 여러 번 불러도 한 번, 던지지 않음)을 마친 뒤 보낸다(늦은 재발급 응답의 refresh 쿠키가 카카오 로그인 쿠키를 덮지 않게). 카카오가 붙인 `error`(사용자 취소) · 값 없음은 보내지 않는다. 결과: `LOGGED_IN` → 응답의 `AuthToken` 필드만 골라 `setSession` → 카카오로 떠나기 전에 둔 돌아갈 곳(`login-return-store.ts` 의 `afterKakaoLoginPath`, 없으면 홈, #140), `SIGNUP_REQUIRED` → `/setup/region?from=kakao`, `LINK_REQUIRED` → 가린 이메일을 첫 진입 Provider 메모리에 두고 `/login/kakao/link`. 로그인 응답 필드가 빠졌거나 모르는 결과 · 이메일 없는 `LINK_REQUIRED` 는 거부한다.
  - **실패는 모두 `/login?error=kakao-fail`** 이다. 화면이 사유를 따로 알리는 것만 허용 목록 값으로 `&kakao=` 에 싣는다(`login-notice.ts` 의 `KAKAO_FAIL_REASONS`): `AUTH_023` → `email-required`, `024` → `email-unverified`, `025` · `026` → `expired`, `MEMBER_003` → `suspended`. 그 밖(`AUTH_020` · `021` · `022` · `027` · `MEMBER_002` · 저장소 장애 · 일시 장애 · 검증 오류)은 사유 없이 알린다 — 탈퇴는 이메일 로그인처럼 따로 드러내지 않는다.
  - **카카오 가입**(S02-3 `가입하기`): `POST /kakao/signup` 의 응답(`AuthToken`)으로 `setSession` 한 뒤 지금처럼 동네 저장(`saveRegion` — 실데이터 세션이 생겨 요청이 나간다) → S02-4. `AUTH_025` → `kakao-restart`(사유 `expired`), `MEMBER_001`(그사이 같은 이메일 가입) · 그 밖의 서비스 업무 오류(`AUTH_0xx` · `MEMBER_0xx` — 백엔드가 가입표를 먼저 지운다, `kakao-ticket.ts` 의 `kakaoTicketLost`) → `kakao-restart`(사유 없음) — 모두 `/login?error=kakao-fail` 로 카카오 로그인부터 다시 한다. 응답을 받지 못한 실패(`UNAVAILABLE` · `GATEWAY_*`)와 검증 `AUTH_110~112`(가입표를 건드리지 않음)만 거부("가입하지 못했어요", 다시 누름).
  - **계정 연결**(`연결하고 계속하기`): `POST /kakao/link` 응답(`AuthToken`)으로 `setSession` → 홈. `AUTH_026` → 사유 `expired`, `027` · 그 밖의 서비스 업무 오류(확인표를 먼저 지운다) → 사유 없이 `/login?error=kakao-fail`. 응답을 받지 못한 실패(`UNAVAILABLE` · `GATEWAY_*`)만 거부("연결하지 못했어요", 다시 누름). `다른 카카오 계정으로 계속하기` 는 `GET /kakao/authorize?switchAccount=true` 다.
  - `?error=kakao-exists` 는 쓰지 않는다(백엔드가 겹치면 `LINK_REQUIRED` 를 준다). 옛 주소로 오면 알리지 않는다.
  - 한계: FE 로컬(`http://localhost`)은 교차 사이트라 state 쿠키가 실리지 않아 콜백이 `AUTH_020` 으로 끝난다 — dev 웹에서 확인한다. **iOS 홈 화면(standalone) PWA 는 실기기 확인이 필요하다** — 카카오 인가 화면이 앱 안 Safari 시트로 열리면 쿠키 저장소가 달라 state 쿠키가 없을 수 있다(→ `AUTH_020`, 사유 없는 실패).
- 탈퇴 · 건강정보 동의 · 철회 · 약관 재동의는 확정됐다 — 아래 "회원" 의 `POST /me/withdraw`(백엔드 #154)와 동의 API(#59, 프론트 연동 #168). 프론트 `withdrawMembership` 은 #169 에서 연동했다.

## 회원 `/api/v1/members` (확정)

모두 인증 필요(`Authorization: Bearer`).

| 요청                                        | 바디 → `dataBody`                                                                                         | 주요 오류                                                                                                                     | 프론트 함수                                  |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `GET /me`                                   | → `MyInfo`                                                                                                | `MEMBER_004` 회원 없음(404, 로그아웃 상태로), `002` · `003`(403)                                                              | `fetchMyInfo`(회원 정보 저장소)              |
| `PATCH /me`                                 | `{ nickname }` → `MyInfo`                                                                                 | 검증 `MEMBER_101` · `102`                                                                                                     | `updateNickname`                             |
| `POST /me/password`                         | `{ currentPassword, newPassword }` → null (다른 기기 로그아웃)                                            | `MEMBER_005` 불일치(400) · `006` 잠금(429) · `007` 비밀번호 없음(409) · `009`(503), 검증 `103~107`                            | `changePassword`                             |
| `GET /me/region`                            | → `MemberRegion` \| null (아직 고르지 않음)                                                               | `REGION_004` 행정동 확인 장애(503)                                                                                            | `fetchMyRegion`(회원 정보 저장소)            |
| `PUT /me/region`                            | `{ code }`(숫자 8자리) → `MemberRegion` (`abolished` 는 늘 false)                                         | `REGION_001` 없는 코드 · `002` 폐지(400), `003` 동시 첫 저장 경합(409, 다시 보내면 갱신), `004`(503), 검증 `101` · `102`(400) | `saveRegion`                                 |
| `POST /me/consents`                         | `{ type, documentVersion }` → `ConsentStatus` (이미 유효하면 멱등)                                        | `MEMBER_010` 받지 않는 항목(400), `011` 문서 버전이 서버의 지금 버전과 다름(409)                                              | `agreeHealthConsent` · `agreeTermsReconsent` |
| `DELETE /me/consents/SENSITIVE_HEALTH_INFO` | → `ConsentStatus` (모든 기기 로그아웃 · refresh 쿠키 삭제. 철회할 동의가 없으면 멱등 200 · 로그아웃 없음) | `MEMBER_012` 철회할 수 없는 항목(400)                                                                                         | `withdrawHealthConsent`                      |
| `POST /me/withdraw`                         | 본문 없음 → null (모든 기기 로그아웃 · refresh 쿠키 삭제)                                                 | `MEMBER_002` 이미 탈퇴 · `003` 정지(403), `004` 회원 없음(404)                                                                | `withdrawMembership`(연동 #169)              |

- `MyInfo` = `{ memberId, email, nickname, profileImageUrl, provider(EMAIL | KAKAO), hasPassword, role, pendingConsents, reportWritable }`. `profileImageUrl` 은 nullable 이다(아래 "프로필 이미지"). `provider` 는 카카오를 연결한 이메일 계정도 `KAKAO` 다(DB 값이 없으면 `EMAIL`). `hasPassword` 가 true 면 비밀번호 변경을 보이고, false 면(카카오로만 로그인) **비밀번호 메뉴를 숨긴다** — 비밀번호 최초 설정 API(`POST /me/password/setup`)는 백엔드 #61 에서 없앴고(`MEMBER_008` 은 비운 번호), 프론트도 #166 에서 `setupPassword` 와 설정 화면을 지웠다(목도 같은 동작). 재동의 조건 `pendingConsents` 는 로그인 응답과 같은 계산이다.
- **탈퇴**(`POST /me/withdraw`, 백엔드 #154): 성공하면 서버가 이 기기를 포함한 모든 기기를 로그아웃시키고(세션 저장소 장애로 로그아웃이 실패해도 탈퇴는 끝났으므로 200 · 쿠키 삭제) 보고 원본 파기를 요청하며 관심 동네 · 알림 설정을 지운다. 화면은 성공하면 세션을 비우고 비회원 홈으로 간다. `MEMBER_002` 는 이미 탈퇴한 뒤(다른 기기 · 겹친 요청)라 같은 결과로 보고 세션을 비운다. **같은 이메일로 바로 다시 가입할 수 있다** — 탈퇴한 계정으로 이메일 로그인하면 가입되지 않은 계정과 같은 `AUTH_011`, 카카오는 `SIGNUP_REQUIRED` 다(이전 계정의 동의 · 보고 · 설정은 이어지지 않는다).
  - **프론트 연동 (#169)**: `withdrawMembership(source)` — 세션 저장소가 회원이 아니면 요청 없이 거부한다. 성공 · `MEMBER_002`(이미 탈퇴)면 이 기기 세션을 비운다(`clearSession('logout')` · 만료 진행 표시 끔, 동의 철회와 같다 — 회원 정보 · 이번 주 보고 저장소는 세션이 비회원이 되면 스스로 지운다). `MEMBER_004` · `003` 은 거부하면서 내 정보를 다시 읽게 해(`reloadMemberInfo`) 회원 정보 저장소가 세션을 끝내게 한다(닉네임 바꾸기와 같다). 일시 장애 · 401 계열 · 분류 밖 오류는 거부하고 세션을 그대로 둔다(탈퇴가 일어나지 않았다 — 화면은 대화상자 안에서 다시 시도하라고 알린다). 다른 기기가 먼저 탈퇴했다면 서버가 모든 세션의 access 를 막아 이 요청은 401 → 재발급 재로그인으로 끝나고 세션 저장소가 만료로 비운다(로그인 화면) — `MEMBER_002` 는 겹친 요청이거나 앞선 탈퇴의 서버 로그아웃이 저장소(Redis) 장애로 실패했을 때다.
- `MemberRegion` = `{ code, name, sigungu, abolished }`. 이름 · 폐지 여부는 조회할 때마다 행정동 서비스(surveillance)에서 다시 읽는다 — 코드가 없으면 `name` · `sigungu` 가 null 이고 `abolished: true` 다. 폐지돼도 서버는 저장 값을 바꾸지 않는다(다시 고르게 한다). 내 동네는 `/me` 에 싣지 않는다 — `/me` 가 행정동 서비스 장애에 묶이지 않게 따로 읽는다.
- **프론트 연동 (#164)**:
  - **회원 정보 저장소**(`features/auth/member-info.ts`, 요청은 `member-client.ts`): 세션 저장소가 회원이 되면(로그인 · 새로고침 복원 · 다른 탭 로그인 — `memberId` 가 바뀔 때만) `GET /me` 와 `GET /me/region` 을 한 번씩 읽어 메모리에 두고, 비회원이 되면 바로 지운다(늦은 응답은 버린다). 같은 회원의 재발급에는 다시 읽지 않는다. 두 요청은 따로 `loading` · `ready` · `failed` 이고 다시 시도(`retryMemberInfo`)는 실패한 쪽만 다시 읽는다. 내 동네는 다른 탭의 저장 알림 · 화면이 다시 보일 때(60초 제한)도 다시 읽는다(#190, 아래 "주간 보고" 의 한계 · [conventions.md](conventions.md) "회원 정보 저장소"). 루트 레이아웃의 `SessionBootstrap` 이 켠다.
  - `GET /me` 가 `MEMBER_004` 면 세션을 비운다(`clearSession('withdrawn')` — 만료 알림 없이 비회원, 다른 탭에도 알림). 다시 로그인할 계정이 없어 "다시 로그인해 주세요"(`expired`)로 보내지 않는다. 탈퇴 · 정지 `MEMBER_002` · `003`(403)도 세션을 끝낸다 — 세션 저장소의 재발급이 같은 코드를 세션 종료로 보는 것(`endsSession`)과 같은 판단이고, 사유도 재발급과 같은 `clearSession('expired')`(이 탭에 세션이 있었으면 만료 안내로 로그인 화면)다.
  - 프로필 훅(`useMockProfile`, 이름 정리는 후속)은 실데이터면 이 저장소의 `MyInfo`(provider `EMAIL`/`KAKAO` → `email`/`kakao`)를 옮긴다. 읽기 전 · 실패면 null 이고 상태는 `useMockProfileStatus()` 다 — **예시 프로필로 채우지 않는다.** 내 동네 훅(`useMemberRegion`)은 반환 모양(`{ code, name } | null`)을 그대로 두고 저장소의 내 동네를 준다(미설정 · 읽기 전 · 실패 · 이름 모름이면 null, 상태는 `useMemberRegionStatus()`).
  - `saveRegion(district, source)`: 실데이터는 코드만 `PUT` 한다. `REGION_001` · `002` · `101` · `102` → `{ status: 'invalid' }`(다른 동네를 고르게 함), `REGION_003` → 한 번 다시 보냄, `REGION_004` · 일시 장애 · 두 번째 경합 → 거부("바꾸지 못했어요 · 잠시 뒤 다시"). 성공하면 응답을 저장소의 내 동네로 넣는다(다시 읽지 않음 — 먼저 나간 조회의 늦은 응답은 버림). 부르는 화면(가입 마무리 · 내 동네 바꾸기 · 다시 고르기 · 지도 `내 동네로 설정`)이 `useDataSource()` 로 출처를 넘긴다.
  - 회원 조건(`useMemberRequirements`): 실데이터는 세션의 `pendingConsents` → 약관 재동의, 내 동네 `abolished` → 동네 다시 고르기(재동의 다음). 내 동네가 미설정(null)이면 다시 고르게 하지 않는다. 내 동네를 읽는 중이면 동네 조건을 판단하지 않고(`settled: false` — 다시 고르기 화면은 이때 내보내지 않음), 읽지 못했으면 조건 없이 정해진 것으로 본다.
- **프론트 연동 (#166)** — `changePassword(current, next, source)`: `POST /me/password` 를 access 를 실어 보낸다. 성공하면 **이 기기는 그대로**(세션 저장소를 건드리지 않음)이고 다른 기기는 서버가 로그아웃한다 — 내 정보 알림 "비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요".
  - 오류 매핑: `MEMBER_005` · 검증 `103` · `104`(현재 비밀번호 없음 · 100자 초과 — 맞을 수 없음) → `wrong-current`, `006` → `locked`(회색 상자, 잠금 시간은 서버 설정이라 못 박지 않음), 검증 `105~107` → `invalid-password`(새 비밀번호 칸 아래 규칙 문구), `007` → `no-password`(내 정보와 어긋남 — `reloadMemberInfo()` 로 내 정보를 다시 읽고, `hasPassword` 가 false 면 비밀번호 화면이 내 정보로 돌아간다). `009` · 일시 장애 · 분류 밖 오류는 거부("바꾸지 못했어요").
  - 비밀번호 행 · 화면: 실데이터는 내 정보를 읽어 `hasPassword` 가 true 일 때만 행을 보인다(읽는 동안 · 실패 · false 면 없음). `/me/password` 는 `hasPassword` false 면 그리지 않고 내 정보로 돌려보낸다.
- **프론트 연동 (#192)** — `updateNickname(nickname, source)`(요청은 `member-client.ts` 의 `patchMyNickname`): 앞뒤 공백을 지운 닉네임을 `PATCH /me` 로 access 를 실어 보낸다(규칙은 가입과 같은 2~10자, 코드포인트 기준 — 화면이 `signup-rules` 로 먼저 막는다). 세션 저장소가 회원이 아니면 요청 없이 거부한다.
  - 성공하면 응답(`MyInfo`)을 회원 정보 저장소의 내 정보로 바로 넣는다(`setMemberInfo` — 다시 읽지 않음, 먼저 나간 조회 · 다시 읽기의 늦은 응답은 버림, 보낸 회원이 아니면 넣지 않음). 다른 탭에는 알리지 않는다(닉네임은 보고 · 집계에 쓰이지 않는다 — 그 탭은 다음에 내 정보를 읽을 때 맞는다).
  - 오류 매핑: 검증 `MEMBER_101` · `102` → `invalid`(닉네임 칸 아래 규칙 문구). 회원 상태 오류(`MEMBER_004` · `002` · `003`)는 거부하면서 `reloadMemberInfo()` 로 내 정보를 다시 읽어 저장소가 `GET /me` 와 같은 판단으로 세션을 끝내게 한다. 일시 장애 · 분류 밖 오류는 거부("바꾸지 못했어요").
- **프론트 연동 (#168)** — 동의 · 재동의 · 건강정보 동의 철회. `ConsentStatus` = `{ pendingConsents, healthInfoAgreed, reportWritable, purgePending }`. `type` 은 `TERMS_OF_SERVICE` · `PRIVACY_POLICY` · `SENSITIVE_HEALTH_INFO` 만 받는다(만 19세 확인은 가입 때만). **동의해도 지금 access 의 scope 는 그대로**다 — `report:write` · `pendingConsents` 는 `POST /auth/token/reissue` 로 새 access 를 받아야 바뀐다. 세 함수 모두 출처를 받고, 실데이터는 세션 저장소가 회원이 아니면 요청 없이 거부한다. 화면은 응답 본문(`ConsentStatus`) 대신 다시 받은 세션 요약을 쓰고, 응답에서는 `purgePending` 만 읽는다(`true` 일 때만 참 — 모양이 어긋나면 파기 대기가 아닌 것으로 본다).
  - `agreeHealthConsent(consent, source)`(S02-4 · 홈의 증상 보고 동의 시트): `SENSITIVE_HEALTH_INFO` 를 `legal.ts` 의 지금 버전으로 보내고, 성공하면 `refreshSession()`(재발급 한 번 — 일시 장애는 삼킴)으로 세션 요약을 다시 받는다. 결과 `{ status: 'ok', reportWritable, purgePending }` 의 `reportWritable` 은 다시 받은 요약의 값, `purgePending` 은 동의 응답의 값이다. 거짓이면 보고 흐름을 열지 않는다(홈이 미동의로 보고 시트로 되돌리지 않게) — 파기 대기면 S02-4 · 시트 모두 "지난 보고를 지우는 중이라 아직 보고할 수 없어요" 를 보이고, 그 밖(재발급 일시 장애 등)이면 S02-4 는 보고 진입 없이 같은 동네 홈으로, 시트는 "보고는 잠시 뒤 다시 시도해 주세요" 를 보인다. `MEMBER_011` → `{ status: 'outdated' }`("동의 내용이 바뀌었으니 새로고침해 주세요"), 그 밖은 거부("동의를 보내지 못했어요").
  - `agreeTermsReconsent(source)`(Setup-3-reconsent): 세션 요약의 `pendingConsents` 중 `TERMS_OF_SERVICE` · `PRIVACY_POLICY` 를 각각 지금 버전으로 차례로 보내고(모르는 항목은 보내지 않음) 모두 성공하면 `refreshSession()` 으로 `pendingConsents` 를 비운다 — 안 하면 조건 가드가 재동의 화면으로 되돌린다. 다시 동의할 항목이 없으면 보내지 않고 `ok`. 모르는 항목이 하나라도 있으면 아는 항목은 보내되 `outdated`(서버가 이 배포가 모르는 항목을 요구 — 상수가 낡음. `ok` 면 가드가 되돌리는 일이 되풀이된다)이고, 아는 항목이 없으면 보내지 않는다. 화면은 이용약관만 보이는데 처리방침도 보낸다 — 처리방침을 아직 개정하지 않아 pending 에 들어올 수 없고, 개정하면 버전 고정 테스트가 화면 수정을 강제한다. 어느 항목이든 `MEMBER_011` → `outdated`("약관이 바뀌었으니 새로고침해 주세요", 남은 항목은 보내지 않음), 그 밖은 거부("약관 동의를 보내지 못했어요" — 먼저 보낸 항목은 서버에 남고 다시 보내도 멱등).
  - `withdrawHealthConsent(source)`(내 정보 확인 대화상자): `DELETE /me/consents/SENSITIVE_HEALTH_INFO`. 성공하면 `clearSession('logout')`(세션 저장소가 아직 회원일 때) · 만료 진행 표시를 끄고 다른 탭에 알린다 — 회원 정보 · 이번 주 보고 저장소는 세션이 비회원이 되면 스스로 지운다. **철회할 동의가 없는 멱등 200 에도 세션을 비운다.** 다른 기기가 먼저 철회했으면 서버가 모든 세션의 최근 access 를 막아 이 기기는 401 을 받는다(아래 거부). 멱등 200 은 앞선 철회의 세션 폐기가 Redis 장애로 실패했거나 요약이 낡았을 때 나오고, 이때 이 기기의 refresh 세션이 서버에 남을 수 있다(로그인한 기기 목록에 만료까지 남음) — 그래도 화면은 철회 결과를 따라 비회원으로 간다. 일시 장애 · 401 계열 · 분류 밖 오류는 거부하고 세션을 그대로 둔다(철회가 일어나지 않았다 — 로그아웃과 달리 "세션이 이미 없음" 을 성공으로 보지 않는다. 재발급이 재로그인으로 끝났으면 세션 저장소가 이미 비우고 만료 이동이 맡는다).
  - 한계: 파기 실행(#155) 전에는 철회 뒤 다시 동의해도 미완료 파기 요청 때문에 `reportWritable` 이 false 라 보고할 수 없다. 응답의 `purgePending` 으로 보이는 "보고를 지우는 중" 홈(Home-purging)은 다음 단계다. `refreshSession()` 이 동의 커밋 전에 나간 진행 중 재발급(탭 안 · 탭 사이 잠금 대기 중 다른 탭의 결과)에 묶이면 동의 전 요약을 받을 수 있다 — 시트는 "잠시 뒤 다시" 안내, 재동의는 가드에 한 번 되돌려지고 다시 누르면 풀린다.
- **프로필 이미지** (확정 — 백엔드 #112, 프론트 연동 전. 시안에 업로드 화면이 아직 없다):
  - `POST /me/profile-image` — `multipart/form-data`, 파트 이름 **`imageFile`** 하나 → `{ profileImageUrl }`(방금 올린 이미지의 공개 URL, `MyInfo.profileImageUrl` 과 같은 값). 이전에 올린 이미지는 서버가 지우고, 소셜 프로필 URL 은 비운다.
  - 형식은 jpg · png · gif · webp 이고 **파일 내용(매직 바이트)으로 판정한다** — 확장자 · `Content-Type` 은 보지 않는다. 크기는 **5MiB(5,242,880바이트)까지**다.
  - 오류: `STORAGE_001` 파일 · 파트 없음 · 빈 파일, `STORAGE_002` 5MiB 초과, `STORAGE_003` 지원하지 않는 형식(400), `STORAGE_004` 저장소 장애(500), 회원 상태 `MEMBER_004`(404) · `002` · `003`(403). 요청이 **6MB(nginx · 서버 요청 상한)를 넘으면 봉투 없는 413** 이다 — 화면이 보내기 전에 5MiB 를 확인한다(봉투가 없는 응답은 일시 장애로 다룬다).
  - `DELETE /me/profile-image` → null. 올린 이미지 · 소셜 URL 을 모두 비운다. **이미지가 없어도 200(멱등).** 오류는 회원 상태뿐이다.
  - 표시: `GET /me` 의 `profileImageUrl` — 올린 이미지의 공개 URL 이 소셜 URL 보다 앞서고, 둘 다 없으면 null(기본 이미지를 보여 준다). 공개 URL 은 `{MinIO 공개 주소}/{버킷}/members/profiles/...` 꼴이고 인증 없이 읽힌다(키를 모르면 찾을 수 없다).

### 관심 동네 `/me/interest-regions` (확정 — 백엔드 #217, 화면 #198, 연동 #237)

내 동네 말고 지켜볼 행정동이다. 정본은 [`backend/docs/modules.md`](../../backend/docs/modules.md) "관심 동네" 와 #198 의 백엔드 결정(2026-10-08)이다.

```text
GET    /api/v1/members/me/interest-regions          → SliceResponse<MemberRegion>   (인증, 고른 순서)
POST   /api/v1/members/me/interest-regions  { code } → SliceResponse<MemberRegion>   (인증, 목록 끝에 더한 뒤의 목록)
DELETE /api/v1/members/me/interest-regions/{code}    → SliceResponse<MemberRegion>   (인증, 뺀 뒤의 목록, 없어도 성공)
```

- `MemberRegion` 은 내 동네와 같은 `{ code, name, sigungu, abolished }` 다. 행정동 코드만 저장한다 — 위치 · 주소를 받지 않는다. 인증만 요구하고 `report:write` 는 요구하지 않는다(건강정보가 아니다 — 동의하지 않은 회원도 쓴다).
- 세 요청 모두 **바뀐 뒤의 목록**을 준다 — 화면이 더하고 뺀 결과를 다시 읽지 않고 그대로 그린다. 목록은 `SliceResponse { contents, hasNext }` 이고 상한만큼만 있어 한 번에 모두 준다(`hasNext` 는 늘 false — 프론트는 보지 않는다). 하나도 없으면 빈 `contents` 다(`dataBody: null` 이 아니다).
- **상한 3곳** — 서버 설정 `region.interest.max-count`(기본 3, 환경마다 바꾸지 않음)와 프론트 `INTEREST_REGION_LIMIT` 이 같은 값이다. 바꾸면 함께 바꾼다.
- 결정(2026-10-08): 내 동네와 같은 동네는 **서버도 막는다**(`REGION_007`). 내 동네를 관심 동네 중 하나로 바꿔도 그 관심 동네는 **남는다**(`PUT /me/region` 은 관심 동네를 건드리지 않는다 — 화면이 그 줄을 `내 동네` 로 적는다). 폐지된 관심 동네는 **서버가 지우지 않고** `abolished: true` 로 남는다(이름이 남아 있으면 이름도 온다). 행정동 서비스가 코드를 모르면 `name` · `sigungu` 가 null(`abolished: true`)이다 — 내 동네와 같은 규칙이다.
- `GET` 은 동네마다 행정동 서비스를 차례로 읽어 하나라도 장애면 `REGION_004`(503)다.

| 오류                                     | 상태 | 뜻                                                          | 프론트                                                                                                              |
| ---------------------------------------- | ---- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `REGION_005`                             | 409  | 상한(3곳) 초과                                              | `GET` 으로 다시 읽어 `{ status: 'limit', regions }`                                                                 |
| `REGION_006`                             | 409  | 이미 고른 동네                                              | `GET` 으로 다시 읽어 `{ status: 'duplicate', regions }`                                                             |
| `REGION_007`                             | 409  | 내 동네와 같음                                              | `GET` 으로 다시 읽어 `{ status: 'home', regions }`                                                                  |
| `REGION_003`                             | 409  | 같은 계정의 추가가 겹침(다시 보내면 풀림)                   | 한 번 다시 보낸다. 두 번째도 겹치면 거부                                                                            |
| `REGION_001` · `002`, 검증 `101` · `102` | 400  | 없는 코드 · 폐지 · 형식(`DELETE` 의 경로 코드 형식도 `102`) | 추가는 `{ status: 'invalid' }`, 삭제는 거부                                                                         |
| `REGION_004`                             | 503  | 행정동 확인 장애. 추가 · 삭제는 **커밋된 채** 올 수 있다    | 추가는 `GET` 으로 맞춰 그 동네가 있으면 `ok`, 없으면 `failed`(목록과 함께), 다시 읽기도 실패하면 거부. 그 밖은 거부 |

- 409 오류 봉투에는 목록이 없다(`dataBody: null`) — 그래서 거절 뒤 `GET` 으로 다시 읽는다. 다시 읽기가 실패하면 거부한다. 다시 읽은 목록이 거절과 맞지 않으면(`006` 인데 목록에 없음 · `005` 인데 상한 미만 — 그사이 다른 곳에서 지움) `{ status: 'failed', regions }` 다(화면은 목록을 맞추고 "더하지 못했어요").
- 커밋된 채 503: 삭제는 멱등이라 다시 누르면 성공한다. 추가를 다시 보내면 `REGION_006` 이 된다 — 그래서 추가의 `REGION_004` 는 바로 `GET` 으로 맞춘다(목록에 없으면 `failed`, 다시 읽기도 실패하면 거부 — 화면에서 다시 누르면 `duplicate` 로 목록이 맞는다).
- 프론트 함수: `features/region/interest-region-client.ts` 의 `listInterestRegions(source, { scenario })` · `addInterestRegion(district, source)` · `removeInterestRegion(code, source)`. 실데이터는 위 계약대로 부르고, 일시 장애 · 인증 오류 · 분류 밖 오류는 거부한다(화면은 "잠시 뒤 다시 시도"). 목은 모듈 메모리 목록이고 목 세션이 비회원이 되면 지운다(목도 내 동네와 같으면 `home` 으로 거절한다).
- 화면 표시(`features/me/interest-regions-screen.tsx`, [SCREENS.md](design/SCREENS.md) "관심 동네"): `name` 이 null 인 줄은 이름 자리에 `없어진 동네`, 시군구 자리에 `행정구역 개편으로 바뀌었어요` 를 보이고 `삭제` 는 그대로 둔다(이름 `없어진 동네 삭제`). 폐지(`abolished: true`, 이름 있음)는 이름 · 시군구 뒤에 `없어진 동네` 를 붙인다. 둘 중 하나라도 있으면 목록 아래 회색 상자로 지우고 새로 고르게 안내한다. 지어낸 이름 · 코드를 보이지 않는다 — 이름 null 줄이 둘 이상이면 `삭제` 이름에만 순번을 붙인다(`없어진 동네 1 삭제`). `home` 거절을 받았는데 화면이 아는 내 동네가 다르면 회원 정보 저장소의 내 동네를 다시 읽는다(`reloadMemberRegion`). 결과 목록의 `· 관심 동네` 표시 · 이미 고름 판단은 코드로 한다.

### 알림 설정 `/me/notification-settings` (확정 — 백엔드 #218, 화면 #195, 연동 #250)

알림 설정 화면(`/me/notifications`)의 두 스위치다. 정본은 [`backend/docs/modules.md`](../../backend/docs/modules.md) "알림 설정" 과 [`backend/docs/entity-design.md`](../../backend/docs/entity-design.md) §1-7 이다. **알림을 받을지 고른 값(설정)과 실제로 보낼 곳(푸시 구독)은 따로다** — 설정은 이번 단계, 구독 · 발송은 2단계다. 설정을 켜도 지금은 알림이 가지 않는다.

```text
GET   /api/v1/members/me/notification-settings                                   → NotificationSettings   (인증)
PATCH /api/v1/members/me/notification-settings  { weeklyReport?, regionNotice? } → NotificationSettings   (인증, 보낸 항목만 바꾼 뒤의 설정 전부)
```

- `NotificationSettings` = `{ weeklyReport: boolean, regionNotice: boolean }`. 항목은 시안(Settings) 알림 섹션의 둘이다 — `weeklyReport` 주간 보고 요청(월요일 아침), `regionNotice` 검토를 마친 동네 안내(운영자가 발행했을 때). 인증만 요구하고 `report:write` 는 요구하지 않는다(건강정보가 아니다 — 동의하지 않은 회원도 쓴다).
- **처음 값은 모두 false 다** — 한 번도 바꾸지 않은 회원은 `GET` 이 모두 false 를 준다(서버는 이때 행을 만들지 않는다). 알림은 켠 사용자에게만 보낸다(루트 `CLAUDE.md`).
- `PATCH` 는 보낸 항목만 바꾼다(`PUT` 으로 전부를 바꾸면 다른 탭 · 기기에서 바꾼 다른 항목을 덮는다). 응답은 바뀐 뒤의 설정 전부라 화면이 다시 읽지 않고 그린다. 서버는 **보낸 항목만 DB 에서 바꾼다** — 다른 탭 · 기기의 요청이 동시에 다른 항목을 바꿔도 둘 다 남는다. 다시 하는 것은 **첫 변경(설정을 처음 저장할 때)이 겹친 경우뿐**이고 서버가 한 번 다시 한 뒤에도 겹치면 `MEMBER_013` 이다.
- 결정(#218):
  - 켠 시각 · 끈 시각을 **동의 이력으로 남기지 않는다** — 서비스 알림의 수신 선택이고 광고성 정보가 아니다. 법적 의미의 알림 수신 동의는 2단계 푸시와 함께 따로 받는다(`PUSH_NOTIFICATION`).
  - **건강정보 동의를 철회해도 설정은 그대로다**(다시 동의했을 때 다시 켤 필요가 없게). 2단계 발송 때 `weeklyReport` 는 건강정보 동의가 유효한 회원에게만 보낸다 — 화면은 동의 여부로 스위치를 바꾸지 않는다.
  - **탈퇴하면 설정을 지운다**(서버의 탈퇴 처리 #154).
  - `regionNotice` 는 스위치 하나다. 내 동네만인지 관심 동네(위 "관심 동네")도 포함하는지는 2단계 발송 대상 규칙에서 정하고, 이 설정의 모양과 무관하다(기획 확인 후보 — SCREENS.md "홈 화면 추가 · 알림 미지원").

| 오류                         | 상태      | 뜻                                                                                                         |
| ---------------------------- | --------- | ---------------------------------------------------------------------------------------------------------- |
| `MEMBER_111`                 | 400       | 항목이 하나도 없는 본문 `{}` (`fieldErrors[0].field = request`)                                            |
| `MEMBER_112` · `113`         | 400       | `weeklyReport` · `regionNotice` 가 JSON `true` / `false` 가 아님 — 문자열 `"true"` · 숫자 · `null` 도 거절 |
| `MEMBER_114`                 | 400       | 모르는 항목이 있음(`field = unknownItems`, 아는 항목도 바꾸지 않는다)                                      |
| `MEMBER_100`                 | 400       | 깨진 JSON · 객체가 아닌 본문 · 본문 없음                                                                   |
| `MEMBER_013`                 | 409       | 같은 계정의 첫 변경이 겹침(서버가 한 번 다시 한 뒤에도) — 다시 보내면 풀림                                 |
| `MEMBER_004` · `002` · `003` | 404 · 403 | 회원 없음 · 탈퇴 · 정지 — 내 정보(`GET /me`)와 같다                                                        |

- **2단계 — 푸시 구독(이번 범위 아님)**: 1단계에서 이미 켠 회원에게는 구독 · 권한 · 수신 동의가 없다 — 2단계를 열 때 그 회원에게 다시 받는 흐름을 함께 정한다(화면은 지금 "알림을 보내기 시작하면 다시 알려 드려요" 라고 알린다). 사용자가 스위치를 켤 때 서비스 워커 등록 → 알림 권한 요청 → `pushManager.subscribe` 를 거쳐 구독을 등록한다. 가정한 모양은 `POST /api/v1/members/me/push-subscriptions { endpoint, keys: { p256dh, auth } }`(기기마다 하나) · `DELETE /api/v1/members/me/push-subscriptions?endpoint=`. 서버는 **설정이 켜졌고 구독이 있는 회원에게만** 보낸다. 구독이 없거나 받을 수 없는 기기(iOS 홈 화면 추가 전 등)는 서비스 안에서 같은 내용을 본다(홈의 동네 안내 · "알림 대신 여기서 알려드려요"). VAPID 공개 키 전달 · 만료된 구독(410) 정리 · 로그아웃한 기기의 구독 삭제를 함께 정한다.
- 프론트 함수: `features/notification/notification-settings-client.ts` 의 `getNotificationSettings(source, { scenario })` · `updateNotificationSetting(topic, enabled, source)`. 둘 다 설정 전부(`NotificationSettings`)를 준다. 실데이터는 위 계약대로 부르고 바꾸기는 그 항목만 보낸다. `MEMBER_013` 은 한 번 다시 보내고, 그 밖(검증 · 일시 장애 · 인증 오류 · 두 번째 경합)은 거부한다(화면은 읽기 실패면 "불러오지 못했어요" + `다시 시도`, 바꾸기 실패면 "바꾸지 못했어요" — 값 그대로). 푸시 발송 전이라 화면은 켤 수 있을 때 "알림은 아직 보내지 않아요" 를 함께 보인다. 목은 모듈 메모리 설정이고 목 세션이 비회원이 되면 지운다.

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
  - 내 동네가 폐지됐을 때 다시 고르게 하는 흐름(Setup-1-reselect)은 이 API 가 아니라 내 동네 조회(`GET /api/v1/members/me/region` 의 `abolished`, 위 "회원")로 판정한다(#164). 다시 고른 코드가 폐지 · 없는 코드면 저장이 `REGION_002` · `001` 로 거절된다.
- **BE 미정**: 폐지된 동의 후속 후보(`listSuccessorDistricts`) — 출처와 무관하게 목이다.

## 주간 보고 `/api/v1/reports/current` (확정)

본인의 **이번 주** 보고 하나다. 모두 인증 필요(`Authorization: Bearer`) **+ 건강정보 동의(scope `report:write`)** — 건강 · 증상은 민감정보라 조회도 쓰기와 같은 권한이다. 경로 · 본문에 주를 받지 않는다(지난 주 보고는 고칠 수 없고, 읽기는 아래 "지난 보고 내역" `/api/v1/reports/me`). 응답에 회원 ID · 보고자 키 · 보고 ID 를 싣지 않는다.

| 요청     | 바디 → `dataBody`                                                                                     | 주요 오류                                                                                                                                                                                                                    | 프론트 함수                               |
| -------- | ----------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| `PUT`    | `{ districtCode, symptomGroups }` → `WeeklyReport` (이번 주에 처음이면 저장, 있으면 동네 · 증상 바꿈) | `REPORT_002` 없는 행정동 · `003` 폐지된 행정동(400), `001` 같은 주 동시 처리(409 — 서버가 한 번 다시 하고도 졌다), 검증 `REPORT_100`(모르는 증상군 · 깨진 JSON) · `101`~`105`(동네 없음 · 형식 · 목록 없음 · 빈 원소 · 중복) | `submitReport` · `updateReport`           |
| `GET`    | → `WeeklyReport` \| null (아직 보내지 않음)                                                           |                                                                                                                                                                                                                              | `fetchCurrentReport`(이번 주 보고 저장소) |
| `DELETE` | → null (멱등 — 없어도 200, 이번 주 집계에서 빠짐)                                                     |                                                                                                                                                                                                                              | `cancelReport`                            |

- 공통 오류: 토큰 없음 `SECURITY_001`(401), **동의 전(scope 없음) `SECURITY_006`(403)** — 서비스(security-core `CustomAccessDeniedHandler`)가 낸다. 게이트웨이는 토큰이 없으면 통과시키고 인가 판정을 하지 않는다(`JwtErrorCode` 주석).
- 요청 `districtCode` 는 SGIS 숫자 8자리, `symptomGroups` 는 `RESPIRATORY`(호흡기: 발열 · 기침 · 인후통) · `ENTERIC`(장관: 구토 · 설사) 목록이다. **증상 없음은 빈 배열**이고 정상 보고다(건강한 보고가 집계의 분모). null · null 원소 · 중복은 거부된다.
- `WeeklyReport` = `{ isoWeek, districtCode, symptomGroups: [{ code, name, description }], reportedAt, updatedAt }`. `isoWeek` 는 `2026-W40` 처럼 KST 달력 기준 ISO 주(월요일 시작)이고 서버가 정한다. `symptomGroups` 는 선언 순서(호흡기 → 장관). `reportedAt` 은 이번 주 첫 보고 시각, `updatedAt` 은 마지막 수정 시각(ISO-8601 UTC, 초 단위 — 고친 적이 없으면 같다).
- **보고 주는 서버가 받은 시각(KST)으로 정한다**(`ReportWeekCalculator` · `ReportWeek`). 일요일 23:59 KST 에 흐름을 열어 월요일 00:00 KST 뒤에 보내면 새 주로 저장된다. 프론트는 주를 계산하지 않고, 응답 `isoWeek` 가 정본이다.
  - 화면에 보이는 주 라벨(`ReportWeek` 의 `weekRangeLabel` · `reportPeriodLabel`)은 실데이터면 홈이 받은 주간 집계의 `isoWeek`(현재 주)로 만들고, 집계를 받기 전 · 받지 못했으면 화면을 연 때의 KST ISO 주(`kstIsoWeek` — 서버의 보고 주와 같은 정의)로 만든다(#252, `currentReportLabels`). 목데이터는 홈 목의 예시 값(`11월 17일(월)~23일(일)`)이라 서버 `isoWeek` 와 무관하다. 실데이터도 일요일 자정 근처에는 연 시점의 라벨과 저장된 주가 다를 수 있다(받은 보고의 `isoWeek` 로 고칠지는 아직 정하지 않았다).
- **프론트 연동 (#165)**:
  - 요청 · 응답 옮기기는 `features/report/report-api.ts`. 화면 증상 `respiratory` ↔ `RESPIRATORY`, `gastrointestinal` ↔ `ENTERIC`. 보내는 값은 고른 증상뿐이고 주 · 회원 · 위치는 싣지 않는다. 받은 보고는 `SubmittedReport { answer, reportedLabel }` 이고 `reportedLabel` 은 `reportedAt` 을 **KST 날짜** "10월 2일" 로 쓴다(`Intl` `timeZone: 'Asia/Seoul'` — 브라우저 시간대와 무관, UTC 15:00 부터 다음 날). **모르는 증상군 코드가 오면 버리지 않고 실패로 본다** — 버리면 증상 보고가 다른 증상 · 증상 없음으로 보이고, 그대로 고쳐 보내면 서버의 증상이 지워진다.
  - `submitReport` · `updateReport(answer, districtCode, source)` 은 같은 `PUT` 이다. 보고 동네는 **내 동네**(`useMemberRegion()` 의 코드 — 둘러보기 동네가 아님)다. 세션이 회원이 아니거나 동네가 없으면 요청 없이 거부한다. 결과: `ok`(응답을 이번 주 보고 저장소에 바로 넣음) · `consent-required`(`SECURITY_006` — `refreshSession()` 으로 재발급해 세션 요약을 맞춘 뒤) · `region-changed`(`REPORT_003` · `002` — 내 동네를 다시 읽게 한 뒤, `reloadMemberRegion()`). 그 밖(`REPORT_001` · 검증 `REPORT_100`~`105` · 일시 장애)은 거부하고 화면은 "보내지 못했어요. 잠시 뒤 다시 보내 주세요." 다. `REPORT_001` 은 서버가 이미 한 번 다시 했으므로 프론트는 자동으로 다시 보내지 않는다.
  - `REPORT_002`(행정동 서비스에 없는 코드)도 다시 고르기 갈래다 — 내 동네 조회는 그 코드를 `abolished: true` 로 준다(위 "회원"). 다시 읽은 내 동네가 폐지면 회원 조건(`useMemberRequirements`)이 동네 다시 고르기로 보낸다. 다른 탭 · 기기에서 이미 현행 동네로 바꿨으면 다시 읽은 새 동네로 다시 보낼 수 있다(같은 브라우저의 다른 탭이 바꾼 경우는 보통 그 전에 아래 #190 알림으로 이미 새 동네다).
  - `cancelReport(source)` 는 `DELETE` 이고 성공하면 저장소를 비운다. 실패는 거부한다(화면은 "되돌리지 못했어요" + 다시 시도). `SECURITY_006` 이면 보내기처럼 세션 요약을 맞춘 뒤 거부한다.
  - **이번 주 보고 저장소**(`features/report/current-report.ts`): 세션이 회원이고 `reportWritable` 이 true 일 때만 `GET` 을 읽는다 — false 면 요청 없이 비운다(403 을 받으러 가지 않는다). `memberId` 가 바뀌거나 `reportWritable` 이 false → true 가 되면 다시 읽고, 비회원(로그아웃 · 만료 · 다른 탭 로그아웃)이 되거나 보고할 수 없게 되면 바로 지운다. 한 회원에 한 번만 보내고(single-flight) 늦은 응답은 버린다. `loading` · `ready` · `failed` 이고 다시 시도(`retryCurrentReport`)는 실패했을 때만 보낸다. `SessionBootstrap` 이 켠다. 보고 내용은 메모리에만 두고 브라우저 저장소에 남기지 않는다.
  - 화면은 `useSubmittedReport()`(반환 모양 그대로 — 읽는 중 · 실패 · 미보고 모두 null)와 `useSubmittedReportStatus()` 로 읽는다. 목데이터 모드는 지금처럼 `report-client.ts` 의 목 모듈 메모리다.
  - **주가 바뀌면 다시 읽는다**: 저장소는 읽은 값의 주(응답 `isoWeek`, 미보고면 요청을 보낸 때의 KST 주 — `kstIsoWeek`, `src/lib/iso-week.ts`)를 두고, 화면이 다시 보일 때(`visibilitychange` → visible · `focus`) KST 기준 지금 주와 다르면 `loading` 으로 다시 읽는다(같은 주면 요청 없음). 화면을 계속 띄워 둔 채 주가 바뀌면 다음에 다시 보일 때까지는 지난 주 값이다.
  - 읽기가 `SECURITY_006` 이면 `refreshSession()` 으로 요약을 맞춘다(보고할 수 없다고 바뀌면 저장소가 지운다).
  - **되돌리기를 줄지는 PUT 응답으로 정한다**: `report-api.ts` 가 `firstSubmission`(`reportedAt === updatedAt` — 고친 적 없음)을 결과에 싣고, 화면은 이 값이 true 인 `증상 없음` 에만 되돌리기를 준다. 이 탭 저장소가 미보고였어도(읽는 중 · 실패 · 다른 기기 · 탭에서 그 사이 보냄) 서버에 이미 있던 보고를 고친 것이면 주지 않는다 — 되돌리면 그 보고까지 지운다. 한계: 서버 시각이 초 단위라 첫 저장과 같은 초 안에 고친 보고는 첫 저장으로 보인다. 목은 보내기 전에 목 보고가 없었는지다.
  - **다른 곳에서 내 동네를 다른 현행 동네로 바꾼 경우(#190)**: 서버는 보고의 `districtCode` 를 회원의 내 동네와 맞춰 보지 않으므로 낡은 동네로 보내면 그 동네로 집계된다. 그래서 프론트가 내 동네를 다시 읽는다.
    - 같은 브라우저의 다른 탭이 저장하면(`saveRegion` 성공) 탭 사이 알림(`region-changed` — 값 없이 회원 구분만)을 받아 내 동네를 `loading` 으로 다시 읽는다. 그동안은 보내기를 막고("내 동네를 불러오고 있어요"), 다시 읽지 못하면 `failed` 라 보내지 않고 다시 읽는다("불러오지 못해 보내지 못했어요").
    - 다른 기기에서 바꾸면 이 탭이 다시 보일 때(`visibilitychange` → visible · `focus`) 다시 읽는다. 마지막으로 읽은 지 60초가 지났을 때만이고, 읽는 동안 · 실패해도 지금 값을 둔다(낡았는지 모른다).
    - 남은 한계: 화면을 띄워 둔 채(포커스 그대로) 다른 기기에서 바꾸거나, 다시 보인 지 60초 안에 다른 기기에서 바꾸면 다음에 다시 읽을 때까지 낡은 동네로 보낸다. `BroadcastChannel` 이 없는 브라우저는 탭 알림 없이 다시 보일 때 읽기만 남는다. 보내기 직전에는 확인하지 않는다. 근본 해결은 서버가 보고 동네를 회원의 내 동네(`member_region`)로 정하는 것인데 auth ↔ surveillance 계약 변경이라 이 범위 밖이다.

## 지난 보고 내역 `/api/v1/reports/me` (확정 — 백엔드 #216, 화면 #194, 연동 #251)

최근 보고 내역 화면(`/me/reports`)이 쓰는 API 다. **백엔드에 구현돼 있어 확정**이다(`ReportWebController#getMyPastReports`). 경로 · 권한 · 응답 관례는 위 "주간 보고"(`/api/v1/reports/current`)와 같다. 게이트웨이 라우트 `/api/v1/reports/**` 안이다.

```text
GET /api/v1/reports/me   → SliceResponse<WeeklyReport>   (인증 + report:write, 이번 주를 뺀 지난 보고, 최근 주부터)
```

- `contents` 의 `WeeklyReport` 는 위 "주간 보고" 와 같은 모양(`isoWeek` · `districtCode` · `symptomGroups`(metadata `{code, name, description}`) · `reportedAt` · `updatedAt`)이다. 회원 ID · 보고 ID · 수정 횟수를 싣지 않는다. 보고하지 않은 주는 자리가 없다(빈 줄을 채우지 않는다). `districtCode` 는 그 주에 보고한 동네다 — 회원이 내 동네를 바꿔도 지난 보고의 동네는 바뀌지 않는다.
- **이번 주는 빼고 준다 — 서버가 정한 주(KST ISO 주, `Clock` 빈) 기준이다.** 이번 주 보고는 `/reports/current` 가 정본이라 화면은 그 저장소 값으로 그린다(보내기 · 고치기 직후와 어긋나지 않게). 주 경계(월요일 0시 KST) 바로 뒤에는 지난 주였던 보고가 이 목록 맨 위로 온다.
- **보관 창 안의 주만 준다**: 지난 주부터 보관 기간(52주, 이번 주 포함)의 가장 오래된 주까지라 많아야 51건이다. 삭제 스케줄러가 늦어도 보관이 끝난 주는 나오지 않는다.
- 목록은 관례대로 `SliceResponse { contents, hasNext }` 다(architecture-guide §8). 한 번에 모두 준다 — 페이지 파라미터를 두지 않고 `hasNext` 는 늘 false 다(프론트는 보지 않는다). 지난 보고가 없으면 `contents: []` 이고 `dataBody` 가 null 이 아니다. 배열(`List`) 예외(행정동 검색 `DistrictWebController` 선례)는 쓰지 않았다 — 자동완성처럼 프론트 계약이 배열로 정해진 것이 아니고, 보관 기간이 늘면 페이지를 나눌 수 있게 둔다.
- 오류는 `/reports/current` 와 같다: 토큰 없음 `SECURITY_001`(401), 동의 전 `SECURITY_006`(403). 받는 파라미터가 없어 검증 오류는 없다.
- 프론트 함수: `features/report/report-history.ts` 의 `listPastReports(source, { now, empty })`(실데이터는 `report-api.ts` 의 `fetchPastReports`). `contents` 를 서버 순서 그대로 `{ isoWeek, answer }` 로 옮기고 동네 · 시각은 버린다. 한 줄이라도 옮길 수 없으면(모르는 증상군 · 읽을 수 없는 주) `ReportResponseError` 로 목록 전체를 거부하고, 요청 실패도 거부한다(화면은 "지난 보고를 불러오지 못했어요" + `다시 시도`). 목은 예시 이력이다(실데이터에서는 보이지 않는다).

## 행정동 주간 집계 `/api/v1/districts/…/weekly-aggregate(s)` (확정 — 백엔드 #212)

홈(동네 하나)과 지도(시군구 하나)가 한 주의 동네 상태를 읽는다. **인증 불필요**(비회원 둘러보기 · 지도) — 행정동 API 처럼 `auth: false` 로 Authorization 없이 보낸다(만료된 access 로 게이트웨이가 거절하지 않게). 보고자 · 회원 정보는 싣지 않는다. 게이트웨이 라우트는 기존 `/api/v1/districts/**` 다.

| 요청                                                        | → `dataBody`                                                                                  | 주요 오류                                                                                                                       |
| ----------------------------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `GET /{code}/weekly-aggregate?week=` (홈)                   | `{ isoWeek, finalized, publicThreshold, slightDeltaPp, highDeltaPp, district: 동네 집계 }`    | `AGGREGATE_001` 없는 코드(404), `002` 조회할 수 없는 주(400), 검증 `101` 코드 형식(숫자 8자리) · `102` 주 형식                  |
| `GET /weekly-aggregates?sigunguCode=&include=&week=` (지도) | `{ isoWeek, finalized, publicThreshold, slightDeltaPp, highDeltaPp, districts: 동네 집계[] }` | `AGGREGATE_001` 없는 `include`(404), `002`, 검증 `103` 시군구 코드 없음 · `104` 형식(숫자 5자리) · `101` `include` 형식 · `102` |

- **동네 집계** = `{ code, name, sigungu, active, level, insufficientReason?, participantCount, calculatedAt?, symptomaticCount?, symptomRate?, baselineRate?, groups?, publishedAdvisory? }`. `level` · `insufficientReason` · 증상군 · 변화는 `{ code, name, description }` 이다.
  - `publishedAdvisory` = `{ isoWeek, title, publishedAt }`(#215) — 운영자가 발행해 **지금 보이는(PUBLISHED)** 안내의 근거 주 · 제목 · 발행 시각(ISO-8601 UTC, 초 단위)이다. **요청 주의 안내, 없으면 요청 주 직전 주의 안내**다(요청 주가 먼저). 안내는 마감된 주의 집계로만 만들어지므로, 기본 호출(현재 주 — 아직 마감 전)에는 보통 지난주 안내가 붙는다. 그보다 오래된 주 · 철회됐거나 · 승인 전이면 **키가 없다** — 키가 없으면 발행 안내 없음(`PublishedNotice` 는 `null`)으로 읽는다. **자료 부족 칸에도 붙는다** — 안내는 운영자가 승인한 정보이고 근거 주의 마감 · 자료 충분 집계로 한 판단이라, 요청 주가 자료 부족이어도 보인다(요약에 수치는 없다). 링크(`/notice/[region]/[week]`)의 주는 요청 주가 아니라 이 `isoWeek` 로 만든다. 지도의 `이 동네 안내 보기` 는 이 키가 있는 동네에만 두고, 본문 · 인용 수치 · 정정 이력은 아래 "동네 안내" 를 이 `isoWeek` 로 부른다.
  - `level` 은 `NORMAL` · `SLIGHT` · `HIGH` · `INSUFFICIENT` 이고 프론트 `status`(normal · slight · high · insufficient)와 1:1 이다.
  - **`INSUFFICIENT` 면 `symptomaticCount` · `symptomRate` · `baselineRate` · `groups` 가 응답에 아예 없다**(키가 없다 — null 이 아니다). 집계 행에 숫자가 있어도 서버가 뺀다. 프론트는 이 값이 없을 때 수치 · 상태색을 그리지 않는다.
  - `insufficientReason` 은 `INSUFFICIENT` 일 때만 있고 `LOW_SAMPLE`(참여 100명 미만) · `UNSTABLE`(마감 판정에서 전주 대비 참여 급변) · `NO_BASELINE`(기준선을 만들 마감 4주 부족)이다. **화면은 셋 모두 같은 `자료 부족` 으로 그린다**(이유를 문구로 갈라 보이지 않는다).
  - 그 주 보고가 한 번도 없던 동(집계 행 없음)은 `INSUFFICIENT` · `LOW_SAMPLE` · `participantCount: 0` 이고 `calculatedAt` 이 없다. 오류가 아니다.
  - `participantCount` 는 그 주 참여자 수(증상 없음 보고 포함 — 비율의 분모)이고 자료 부족이어도 있다(참여 진행 막대 `41 / 100명`). `calculatedAt` 은 마지막 재계산 시각(ISO-8601 UTC, 초 단위 — 화면의 `오늘 09:00 갱신`).
  - 비율(`symptomRate` · `baselineRate` · `groups[].rate`)은 **퍼센트, 소수 첫째 자리 반올림(HALF_UP)** 이다(`16.7` = 16.7%). 단계 · 변화 판정은 서버가 센 수끼리 정수로 하므로, 반올림한 두 값의 차가 경계(3.0)처럼 보여도 단계가 다를 수 있다 — 프론트는 다시 계산하지 않는다.
  - `baselineRate` 는 판정에 쓴 기준선(직전 8주 중 마감 · 표본 충분 · 급변 아닌 최근 4주의 합산 비율 — 화면의 `지난 4주 평균`)이다.
  - `groups` = `[{ group, rate, trend? }]`, 호흡기(`RESPIRATORY` — 화면 `respiratory`) → 장관(`ENTERIC` — 화면 `gastrointestinal`) 순서다.
  - `trend` 는 그 증상군 비율의 **전주 대비** 변화 `DOWN` · `FLAT` · `SLIGHT` · `HIGH`(화면 `down` · `flat` · `slight` · `high`, 이름은 `TREND_LABEL` 과 같다)다. 이번 주 − 전주가 `highDeltaPp`(8)%p 이상 HIGH, `slightDeltaPp`(3)%p 이상 SLIGHT, −`slightDeltaPp`%p 이하 DOWN, 그 사이 FLAT(경계 포함). **이번 주와 전주가 모두 수치가 있는 단계일 때만** 있고, 전주 행이 없거나 전주가 자료 부족이면 키가 없다. 한계: 현재 주는 참여가 쌓이는 중인 주이고 전주는 다 찬 주라, 주 초반에는 변화가 크게 흔들릴 수 있다(참여 100명을 넘어야 수치가 나오는 것으로 폭을 줄인다).
  - `active` 는 행정동 단건 조회와 같다 — 폐지된 코드도 200 이고 `false` 다.
- `week` 는 `YYYY-Www`(W01~W53)이고 **생략하면 현재 주**(KST, 보고 주와 같은 정의)다. 조회할 수 있는 주는 **현재 주 ~ 51주 전**(원시 보고 보관 52주와 같은 창)이다. 미래 주 · 그보다 오래된 주 · 그 해에 없는 주(53주가 없는 해의 W53)는 `AGGREGATE_002` 이고 메시지에 조회할 수 있는 범위(`2025-W43 ~ 2026-W42`)가 있다. 응답 `isoWeek` 가 정본이다.
- `finalized` 는 그 주가 마감됐는지다(월요일 00:10 KST 에 지난 주 마감). `false` 면 진행 중인 주이거나 아직 마감 전이라 값이 바뀔 수 있다. 단 전국에 보고가 한 건도 없던 지난 주는 집계 행이 없어 마감되지 않으므로 늘 `false` 다(이때 동네는 모두 자료 부족 · 참여 0 이라 바뀔 값이 없다).
- `publicThreshold`(100) · `slightDeltaPp`(3) · `highDeltaPp`(8)는 백엔드 판정 설정값(`aggregate.*`)이다 — 판단 기준 시트(`thresholds`)에 그대로 쓴다. 규칙 버전이 바뀌면 지난 주 행은 옛 값으로 판정됐을 수 있다(1단계는 규칙 하나).
- **지도 범위는 시군구**다. `sigunguCode` 는 행정동 코드 앞 5자리(SGIS)라 처음 고른 동네 코드에서 바로 만든다. `districts` 는 그 시군구의 **현행** 동 전부(코드 오름차순, 폐지 동 제외)이고, 집계 행이 없는 동도 자료 부족으로 담긴다.
  - **`include`(찾은 동네 · 처음 고른 동네)는 반드시 담긴다**(#204) — 시군구 밖이거나 폐지된 코드여도 목록 맨 앞에 더하고, 이미 목록에 있으면 그 자리 그대로다. 보고가 없으면 자료 부족(참여 0)이다.
  - `districts` 가 빈 배열인 것은 그 시군구에 현행 동이 없고 `include` 도 없을 때뿐이다. 지도의 "자료 없음"(이번 주 동네 자료가 하나도 없음)을 따로 알리는 값은 없다 — 모든 동이 `participantCount: 0` 이면 그렇게 볼 수 있다.
- **아직 없는 것**: 증상군의 최근 주별 비율(`series`, 작은 막대)은 이 응답에 없다. 회원의 내 동네를 서버가 알아서 범위로 쓰지 않는다(프론트가 `include` · `sigunguCode` 로 보낸다).
- **프론트 연동 (#252 — 홈)**: `features/home/weekly-aggregate.ts` 의 `fetchHomeWeekly(code)` 가 `GET /{code}/weekly-aggregate`(`week` 생략 — 현재 주)를 `auth: false` 로 부르고 `toHomeWeekly` 로 `HomeWeekly` 에 옮긴다. 형식이 틀린 코드는 요청 없이, `AGGREGATE_001` · 폐지된 코드(`active: false` — 둘러보기 동네는 지금 있는 동네만)는 null(화면은 "동네를 고르면 …" + `동네 고르기`)이고 그 밖의 실패 · 계약과 다른 응답(모르는 `level` · 읽을 수 없는 `isoWeek` · 수치 단계인데 수치 키 없음)은 거부한다(화면은 빨강 상자 + `다시 시도`). 모르는 증상군 · 변화 코드는 그 줄 · 그 글자만 뺀다(보이기만 하는 값이라 버려도 서버 자료가 바뀌지 않는다 — 보고의 모르는 증상군과 다르다).
  - **누가 읽나**: 실데이터면 서버 페이지(`app/(home)/page.tsx`)가 목 주를 넘기지 않고(`week={null}`) 화면이 읽는다(`features/home/use-home-weekly.ts`). 보이는 동네가 둘러보기 동네(`?region=`)가 아니면 회원의 내 동네인데, 세션 · 내 동네는 화면에만 있어(서버는 refresh 쿠키를 받지 못한다) 서버에서 읽을 수 없기 때문이다. 서버 페이지는 `?region=` 을 모양(숫자 8자리)만 보고 넘기고 행정동 API 로 확인하지 않는다 — 이름은 집계 응답의 `district.name`(머리줄 · 제목도 이 이름), 없는 · 폐지 코드는 위 null 이다. 행정동 API 로 따로 확인하면 그 일시 장애가 "모르는 동네" 로 삼켜져(`districtFromParam`) 홈이 다시 시도 없이 내 동네로 바뀐다. 둘러보기 동네가 없으면 회원 상태가 정해지고(`useAuthSettled`) 내 동네를 읽은 뒤 그 코드로 읽는다 — 둘 다 없으면(비회원 · 내 동네 미정) 요청 없이 동네를 고르라고 안내한다. 내 동네를 읽지 못했으면 빨강 상자이고 `다시 시도` 는 내 동네를 다시 읽는다(`retryMemberInfo`). 목데이터는 지금처럼 `pickHomeMock`(`?mock=`)이다.
  - **옮기는 값**: `level` → `status`(1:1), `INSUFFICIENT` 는 수치 필드가 없는 갈래(`InsufficientWeek`)라 화면이 수치를 그릴 수 없다. 요약 문장은 단계로 정한다(`AGGREGATE_SUMMARY` — 수치 단계는 "증상 보고가 지난 4주보다 조금/많이 늘었어요" · "지난 4주와 비슷한 수준이에요", 자료 부족은 이유와 무관하게 "아직 변화를 판단할 자료가 부족해요"). `symptomaticCount` → `symptomReports`, `slightDeltaPp` · `highDeltaPp` → `thresholds`, `publicThreshold` · `participantCount` 그대로. `groups` → `{ key, rate, trend? }`(`RESPIRATORY` → `respiratory`, `ENTERIC` → `gastrointestinal`, `trend` 키가 없으면 변화 글자를 빼고 그린다). 주차 · 집계 기간 · 보고 기간은 응답 `isoWeek` 로(`11월 3주` · `11월 17일~23일` · `11월 17일(월)~23일(일)`), 갱신 문구는 `calculatedAt` 의 한국 시각(같은 한국 날짜면 `오늘 09:00 갱신`, 아니면 `11월 20일 09:00 갱신`, 없으면 `아직 집계 전`)이다. 오프라인 띠의 받은 시각은 응답을 받은 시각이다.
  - **응답에 없어 비우는 것**: 증상군 주별 비율(`series`) — 실데이터는 작은 막대 대신 `이번 주 14.1%` 글자다. 공식 정보 요약(`official` null — 아래 "공식 정보", #253). 안내의 할 일 목록 · 근거(아래 "동네 안내" 의 "화면 모델과 다른 점").
  - `publishedAdvisory` → `PublishedNotice { publishedLabel: "11월 18일 발행"(publishedAt 의 한국 날짜), title, href }`. 키가 없으면 null. `href` 는 `/notice/{동네 code}/{publishedAdvisory.isoWeek}` 다(#256) — 주는 요청 주가 아니라 안내의 근거 주다(요청 주에 안내가 없으면 직전 주의 안내가 붙는다). 홈 안내 섹션의 `안내 전체 보기` 가 이 주소에 둘러보기 동네(`?region=`)를 이어 간다(목 링크와 같은 `navHref`).
- **프론트 연동 (#257 — 지도)**: `features/map/weekly-aggregates.ts` 의 `fetchMapWeek(code)` 가 `GET /weekly-aggregates?sigunguCode=<code 앞 5자리>&include=<code>`(`week` 생략 — 현재 주)를 `auth: false` 로 부르고, `districts[]` 각 칸을 홈과 같은 `toHomeWeekly` 로 옮겨 `MapWeek { weekLabel, mineCode, mineName, districts }` 를 만든다(`mineName` 은 `include` 칸의 `name`, `weekLabel` 은 응답 `isoWeek`, 동네 순서는 응답 그대로). `code` 는 처음 고른 동네 — 둘러보기 동네(`?region=` — 찾아서 고른 동네도 이 값으로 온다), 없으면 회원의 내 동네다.
  - **누가 읽나 · 실패**: 홈과 같다. 실데이터면 서버 페이지(`app/map/page.tsx`)가 목 지도를 넘기지 않고(`map={null}`, `?region=` 은 모양만) 화면이 읽는다(`useRegionWeekly` — 홈의 `useHomeWeekly` 와 같은 훅). 형식이 틀린 코드는 요청 없이, `AGGREGATE_001` · `include` 칸이 폐지(`active: false`)면 null(동네 고르기 안내)이고, 그 밖의 실패 · 계약과 다른 응답(`include` 칸이 목록에 없음 · 모르는 `level` · 읽을 수 없는 `isoWeek` · 수치 단계인데 수치 키 없음)은 거부한다(빨강 상자 + `다시 시도`). 목데이터는 지금처럼 `pickMapMock`(`?mock=`)이다.
  - **자료 없음**: 모든 칸이 `participantCount: 0` 이면 `districts: []`(지도의 "자료 없음")로 옮긴다 — 주 초 첫 보고 전처럼 집계 행이 하나도 없으면 회색 `자료 부족` 만 늘어선 목록 대신 안내 하나다. 이때는 칸에 `publishedAdvisory` 가 있어도 보이지 않는다(한계).
  - **발행 안내**: 홈과 같은 `PublishedNotice`(근거 주 동네 안내 주소 포함, #256)다 — 지도 동네 정보의 `이 동네 안내 보기` 가 그 주소에 둘러보기 동네(`?region=`)를 이어 간다(목과 같다).

## 공식 정보 `/api/v1/official/latest` (확정 — 백엔드 #213, 화면 S08)

공식 정보 화면(`/official`)과 홈의 공식 정보 행이 쓸 API 다. **백엔드에 구현돼 있어 확정**이다(`OfficialWebController#getLatest`). 게이트웨이 라우트 `/api/v1/official/**`(surveillance) 안이다. **인증 불필요** — 비회원 둘러보기에서도 부른다. 파라미터가 없다.

```text
GET /api/v1/official/latest   → { programs: OfficialProgram[] }   (자료가 없으면 programs: [])
```

```json
{
  "programs": [
    {
      "program": {
        "code": "ARI",
        "name": "급성호흡기감염증",
        "description": "급성호흡기감염증 병원체별 신고 수"
      },
      "source": {
        "code": "KDCA_SENTINEL",
        "name": "질병관리청 표본감시",
        "description": "감염병포털 표본감시 통계에서 받은 자료"
      },
      "sourceUrl": "https://dportal.kdca.go.kr/pot/is/st/ari.do",
      "diseases": [
        {
          "diseaseKey": "ND0704",
          "diseaseName": "호흡기세포융합바이러스",
          "diseaseGroup": "바이러스",
          "metric": { "code": "CASE_COUNT", "name": "발생 수", "description": "신고된 발생 건수" },
          "regionLevel": {
            "code": "NATION",
            "name": "전국",
            "description": "전국 단위 값. 지역 코드는 00"
          },
          "regionCode": "00",
          "regionName": "전국",
          "periodYear": 2026,
          "periodWeek": 38,
          "periodStart": "2026-09-13",
          "periodEnd": "2026-09-19",
          "syncedAt": "2026-09-24T21:00:04Z",
          "values": [
            {
              "ageGroup": {
                "code": "ALL",
                "name": "전체",
                "description": "연령 구분 없는 전체 값. 기본값"
              },
              "value": 152.0
            }
          ]
        }
      ]
    }
  ]
}
```

- **질병관리청 자료만 싣는다.** 시민 자가보고(참여 · 증상 비율 · 단계)는 이 응답에 없고, 집계 API 와 섞지 않는다.
- `programs` 는 batch 가 적재한 프로그램 중 자료가 있는 것만, `NOTIFIABLE`(전수신고) → `INFLUENZA_ILI`(인플루엔자 의사환자 분율) → `ARI`(급성호흡기감염증) → `ENTERIC`(장관감염증) 순이다. `diseases` 는 감염병 키 순, `values` 는 연령대 순(`ALL` 먼저). **무엇을 몇 개 보일지는 화면이 고른다** — 서버는 적재된 주 단위 계열을 모두 준다(전수신고는 감염병 67개 안팎, 표본감시는 병원체별 값과 `TOTAL`(계)).
- 계열(감염병)마다 **가장 최근 기준 주 하나**다. 계열끼리 기준 주가 다를 수 있다(표본감시는 질병관리청이 아직 `집계 중` 인 칸을 적재하지 않는다).
- 기준 주는 **질병관리청 주차**(`periodYear` · `periodWeek`, 일요일 시작 · 1월 1일이 든 주가 1주)와 날짜(`periodStart` 일요일 ~ `periodEnd` 토요일)다. 시민 자가보고의 `isoWeek`(월요일 시작)와 하루 어긋나므로 섞지 않는다. 시안의 "47주" · "11월 17일~23일" 은 이 값으로 만든다.
- 집계 단위는 `regionLevel` · `regionName` 이고 지금은 전국(`NATION`, `00`)뿐이다(행정동 단위가 아니다).
- 값(`values[].value`)은 원값 그대로이고 단위는 `metric`(`CASE_COUNT` 신고 수 · `ILI_PER_1000` 외래 1,000명당 의사환자 분율)이다. 원천이 빈 칸이면 `null` 이다(0 과 다름). 인플루엔자 분율은 연령대별 7개만 오고 전체(`ALL`) 값이 없다. 공공누리 제4유형이라 출처(`source.name`)를 밝히고, 전주 대비 같은 파생값을 보이면 파생값임을 밝힌다(서버는 파생값을 만들지 않는다).
- **전수신고는 지금 주에서 2주 전까지만** 준다 — 신고 지연으로 최근 주 값이 낮게 나와 감소 추세처럼 보이기 때문이다(백엔드 설정 `official.notifiable.display-lag-weeks`). 표본감시는 지연을 두지 않는다.
- `sourceUrl` 은 확인된 원문 화면(감염병포털 표본감시 화면)만 싣는다. **전수신고는 `null`** 이다(원천이 공공데이터포털 API 라 같은 값을 보이는 화면을 확인하지 않았다) — `원문 보기` 를 그리지 않는다.
- `syncedAt` 은 그 값을 **우리가 마지막으로 적재한 시각**(ISO-8601 UTC, 초 단위)이다. 질병관리청 발표일이 아니다. 적재가 실패 · 지연되면 마지막으로 적재된 값과 그 시각이 그대로 온다 — 서버는 "오래됨" 을 판정하지 않으므로 화면이 시각으로 판단한다(적재는 표본감시 매주 금 · 전수신고 매주 화, 설정).
- 자료가 하나도 없으면 200 + `{ programs: [] }` 이다(`dataBody` 는 null 이 아니다). 화면의 `empty`(이유 `none` — 받은 자료 없음)에 대응한다. 받았지만 화면이 보일 표본감시 묶음이 없으면(전수신고만 등) `empty` 의 `unshown` 이다(#253).
- **화면 초안과 다른 점(스키마 공백)**: 발표 단계("유행주의보 발령 중") · 쉬운 요약 문장 · 발표일은 **응답에 없다** — `official_surveillance` 에 출처가 없어 지어내지 않는다. 단계는 출처가 확정된 뒤, 요약은 운영자 검토를 거치는 AI 초안 흐름에서 정한다(루트 CLAUDE.md "공식 정보와 안내"). 화면은 단계 · 요약 없이 수치 · 기준 주 · 출처로 그린다(#253, [SCREENS.md](design/SCREENS.md) "공식 정보 (S08)").
- 오류: 받는 파라미터가 없어 검증 오류가 없다. 공통 오류(게이트웨이 · 일시 장애)만 있다.
- 프론트 함수(#253): `features/official/official-client.ts` 의 `getLatestOfficial(source, mock?)` — 실데이터는 이 API 를 `auth: false` 로 부르고 `official-map.ts` 의 `toOfficialReport` 로 화면 모델(`OfficialReport`)로 옮긴다. 공식 정보 화면(`app/official/page.tsx`)이 서버에서 부르고, 실패(일시 장애 · `programs` 가 배열이 아닌 응답)는 거부해 루트 오류 경계(`다시 시도`)가 받는다. 목데이터는 `?mock=published|empty`(`features/official/mock.ts` — 예시 응답을 같은 매핑으로 옮긴 값)이고 실데이터는 `?mock=` 을 듣지 않는다. 보일 프로그램 · 계열 · 줄을 고르는 규칙은 [SCREENS.md](design/SCREENS.md) "공식 정보 (S08)".
- 홈의 공식 정보 행(#252): 홈은 이 API 를 부르지 않고 요약(발표 단계 · 감염병)을 두지 않는다 — 목 · 실데이터가 같은 모양이다(#260, 홈 타입의 `OfficialSummary` 는 없앴다). 지어낸 단계를 보이지 않고 `질병관리청 공식 정보` · "감염병 발생 현황 · 시민 자가보고와 따로 봐요" 로 `/official` 에 가는 행만 둔다(태블릿 공식 정보 칸은 그리지 않는다). 행을 빼지 않은 것은 홈이 공식 정보 화면으로 가는 주된 길이기 때문이다(모바일 · 태블릿 · 데스크톱 모두). 이 응답에는 단계가 없고 공식 정보 화면도 단계 · 예방수칙 없이 수치만 보이므로, 홈 목의 예시 발표 행은 걷었고 동네 안내의 `공식 예방수칙 보기` 는 `질병관리청 공식 정보 보기` 로 바꿨다(결정, #260). 목 홈의 링크는 공식 정보 목 예시(`?mock=published`)로 이어 진다.

## 동네 안내 `/api/v1/advisories` (확정 — 백엔드 #215)

동네 안내(S07 `/notice/[region]/[week]`)가 읽는 API 다. **백엔드에 구현돼 있어 확정**이다(`AdvisoryWebController#getRegionAdvisory`). 게이트웨이 라우트 `/api/v1/advisories/**`(surveillance) 안이다. **인증 불필요** — 비회원 둘러보기에서도 부른다(행정동 API 처럼 `auth: false`). 화면 동작은 [SCREENS.md](design/SCREENS.md) "동네 안내" 다.

```text
GET /api/v1/advisories?districtCode=&week=   → 발행 중인 안내 | 철회 표시 | null
```

- **파라미터는 둘 다 필수**다. `districtCode` 는 행정동 `code`(SGIS 숫자 8자리 — 주소의 `[region]`), `week` 는 ISO 주 `YYYY-Www`(주소의 `[week]` — 홈 · 지도에서 열 때는 집계 응답 `publishedAdvisory.isoWeek`). 안내는 마감된 주의 집계로만 만들어져 "현재 주" 기본값은 늘 안내 없음이라 두지 않는다. 조회할 수 있는 주는 **현재 주 ~ 51주 전**(행정동 주간 집계와 같은 창)이다.
- `dataBody` 는 셋 중 하나다. `advisory` · `retraction` 키는 늘 있고 하나만 값이 있다.
  - **발행 중**: `{ districtCode, isoWeek, advisory: { title, body, publishedAt, citation, corrections: [{ correctedAt }] }, retraction: null }`
  - **철회됨**: `{ districtCode, isoWeek, advisory: null, retraction: { retractedAt } }` — 내린 안내의 제목 · 본문 · 인용 수치는 **싣지 않는다**.
  - **안내 없음**: `dataBody: null` 이다(초안 · 수정 · 승인 단계의 안내도 보이지 않는다).
- `citation` = `{ level, participantCount, symptomaticCount, symptomRate, groups: [{ group, count, rate }] }` — 운영자가 검토할 때 고정한 **인용 수치**(advisory `cited_*`)다. 집계가 다시 계산돼도 바뀌지 않는다. `level` 은 `NORMAL` · `SLIGHT` · `HIGH`(자료 부족 집계로는 안내를 만들지 않아 `INSUFFICIENT` 는 없다), `level` · `group` 은 `{ code, name, description }`, 비율은 행정동 주간 집계와 같은 퍼센트 소수 첫째 자리 반올림, `groups` 는 호흡기 → 장관 순서다.
- 시각(`publishedAt` · `correctedAt` · `retractedAt`)은 ISO-8601 UTC(초 단위)다. 화면 모델의 `publishedOn` · `correctedOn` · `retractedOn`(한국 날짜 `YYYY-MM-DD`)은 매핑 계층이 KST 로 바꿔 만든다. `correctedAt` 은 정정한 안내의 발행 시각이고 `corrections` 는 **최근 것이 먼저**다. 정정이 없으면 빈 배열이다.
- 오류: `ADVISORY_101` `districtCode` 없음 · `102` 형식(숫자 8자리) · `103` `week` 없음 · `104` 형식(`YYYY-Www`), `ADVISORY_008` 없는 코드(404), `ADVISORY_009` 조회할 수 없는 주(400 — 미래 주 · 51주보다 이전 · 그 해에 없는 주, 메시지에 범위). **폐지된 코드는 오류가 아니다**(그 주에 발행된 안내가 있으면 보인다).
- 결정(#215 에서 정한 세 가지):
  - **철회된 안내는 남긴다** — 그 동네 · 주에 발행 중인 안내가 없고 철회된 안내가 있으면 철회 표시(`retraction`)를 준다. 운영자 철회 대화상자의 "사용자 화면에는 '철회된 안내예요'가 남아요" 와 맞는다. 정정으로 내려간 안내(새 안내가 `corrects_advisory_id` 로 이은 이전 안내)는 철회 표시가 아니라 새 안내의 `corrections` 로 보인다.
  - **철회 · 정정 사유는 공개하지 않는다** — 사유는 운영자 메모(advisory_history `memo`)이고 공개 응답은 이력을 읽지 않는다. `NoticeRetraction.reason` · `NoticeCorrection.reason` 은 선택 값이고 실데이터는 늘 비어(키 없음) 화면이 사유 줄을 뺀다(정정은 `11월 19일 정정` 만 — #256 에서 정함, 철회와 같은 결). 운영자 입력란에는 "사용자 화면에는 보이지 않아요" 로 알린다(#246 결정, #267 에서 정정 사유도 같게 확정 — 아래 "운영자 정정 · 발행 이력"). A03 의 정정 대화상자 · 결과 문구도 "사용자 화면에 정정일이 함께 보여요" 로 맞췄다(#271).
  - **한 동네 · 한 주에 보이는 안내는 하나** — 발행 중인 안내는 하나뿐이다(백엔드가 발행을 막는다 — `ADVISORY_007`). 철회 뒤 같은 주에 새 안내가 발행되면 새 안내만 주고 철회 표시는 주지 않는다. 철회된 안내가 여럿이면 가장 최근 철회 하나다.
- **화면 모델과 다른 점**:
  - 시안의 `items`(할 일 목록) · `source`(근거)는 응답에 없다 — 백엔드 안내는 `title` · `body` 한 덩어리다. **#256 에서 정함**: 화면 모델 `PublishedRegionNotice` 는 `items` · `source` 대신 `body` 를 갖고, 화면은 본문을 **줄바꿈을 지킨 문단 하나**로 그린다(`이렇게 해 주세요` 제목 · 번호 목록 · `근거:` 없음). 본문을 줄마다 할 일로 쪼개지 않는 것은 본문 형식이 정해지지 않아(운영자가 승인한 문장 그대로) 할 일이 아닌 줄을 번호 목록으로 그릴 수 있기 때문이다. **1단계는 `body` 한 덩어리를 유지한다**(#267 에서 닫음) — 운영자 처리(#246) · 검토 후보(#266) · 정정(#267) 모두 `title` · `body` 만 받고 목록 · 근거 필드를 두지 않는다. 시안의 할 일 목록 · 근거를 되살리는 것은 본문 형식을 정하는 다음 단계의 일이다.
  - 발행 중 갈래의 `stats`(인용 집계) 중 `participants` · `symptomRate` · 증상군 비율 · `status` 는 `citation` 에서 오지만, **`baselineRate`(지난 4주 평균) · 증상군 `trend` 는 인용값이 아니라 응답에 없다** — 같은 주 행정동 주간 집계에서 읽는다(안내가 있는 주는 마감 주라 그 값도 바뀌지 않는다). 안내 없음 · 철회 갈래의 `stats`(그 주 집계)도 행정동 주간 집계에서 읽는다 — 이 API 는 집계를 싣지 않는다.
  - 동네 이름은 같은 주 집계 응답의 `district.name`, `isoWeek` 는 주소로 안다(`districtCode` · `isoWeek` 는 확인용으로 되돌려 준다 — 프론트는 두 응답의 주가 주소의 주와 같은지만 본다).
  - 홈 요약 모델 `PublishedNotice` 와 집계의 `publishedAdvisory`(`{ isoWeek, title, publishedAt }`)가 다르다 — `items` · `source` 는 응답에 없다(안내 API 에도 없다). **#252 에서 정함**: `items` · `source` 는 선택 필드(목 시안 예시에만 있음)이고 `title` 을 더했다. **#256 에서 정함**: `href` 는 늘 있다 — 실데이터는 `/notice/{code}/{publishedAdvisory.isoWeek}`. 실데이터 홈 안내 섹션은 `운영자 검토` 배지 · 발행일 · 제목 · `안내 전체 보기` 를 그리고, 본문은 요약에 넣지 않는다(동네 안내 화면에서만).
- 안내를 요약해 보이는 곳(홈 안내 섹션 · 지도 `이 동네 안내 보기`)은 위 행정동 주간 집계의 `publishedAdvisory`(요청 주, 없으면 직전 주)로 **발행 중인 안내만** 안다 — 철회된 안내는 키가 없고 `PublishedNotice` 는 `null` 이다.
- **프론트 연동 (#256)**: `features/notice/notice-client.ts` 의 `getRegionNotice(code, week, source, mock?)` — 실데이터는 `features/notice/advisory-api.ts` 의 `fetchRegionNotice` 가 이 API 와 같은 동네 · 주의 행정동 주간 집계(`GET /api/v1/districts/{code}/weekly-aggregate?week=`)를 **동시에** `auth: false` 로 부르고 `toRegionNoticeBody` 로 옮긴다. 동네 안내 페이지(`app/notice/[region]/[week]/page.tsx`)가 서버에서 부른다(공식 정보와 같이 세션과 무관). 목데이터는 지금처럼 `?mock=published|corrected|retracted|none|insufficient` 이고 실데이터는 `?mock=` 을 듣지 않는다.
  - **옮기는 값**: 발행 중 → `notice { title, publishedOn: publishedAt 의 한국 날짜, body, corrections: [{ correctedOn: correctedAt 의 한국 날짜 }](응답 순서 — 최근 것부터) }` · `stats { status: citation.level, participants: citation.participantCount, symptomRate: citation.symptomRate, baselineRate: 집계 baselineRate, groups: citation.groups 의 { key, rate } + 같은 증상군의 집계 trend }`(`symptomaticCount` 는 화면에 쓰지 않는다). 철회됨 → `retraction { retractedOn: retractedAt 의 한국 날짜 }` + 그 주 집계. 안내 없음 → 그 주 집계만. 그 주 집계는 홈과 같은 `toHomeWeekly` 로 읽어 자료 부족이면 수치 필드가 없는 갈래다. 읽을 수 없는 시각은 빈 날짜로 두어 화면이 그 날짜 조각을 뺀다. 모르는 증상군은 그 줄만 뺀다.
  - **오류**: 형식이 틀린 코드는 요청 없이, 안내 `ADVISORY_008` · `009` · 검증(`101`~`104`)이나 집계 `AGGREGATE_001` · `002` · 검증(`101` · `102`) — 어느 한쪽이라도 그 동네 · 주를 모르면 null → 없는 화면(404, 주소 모양이 틀린 주와 같다). 그 밖의 실패(일시 장애 · 게이트웨이)는 거부해 루트 오류 경계(`다시 시도`)가 받는다. 계약과 다른 응답(두 응답의 주가 주소와 다름 · `advisory` · `retraction` 이 모두 null · 인용 단계가 `INSUFFICIENT` 이거나 모름 · 발행된 안내의 주 집계가 자료 부족 · 집계의 모르는 `level`)도 거부한다 — 지어낸 기준선으로 채우지 않는다.
  - **폐지된 동네**: 백엔드와 같이 오류로 보지 않고 그 주 안내 · 집계를 보인다(전에는 행정동 API 로 확인해 404 였다). 목데이터는 행정동 목이 폐지 동네를 모르므로 그대로다.

## 운영자 안내 처리 `/api/v1/advisories/admin` (확정 — 백엔드 #246)

운영자 검토 대기 · 후보 상세(A01–A02)의 처리 동작(초안 만들기 · 수정 저장 · 승인 · 발행)과 발행 이력(A03)의 철회가 부르는 API 다. **백엔드에 구현돼 있어 확정**이다(`AdvisoryOperatorWebController`). 게이트웨이 라우트 `/api/v1/advisories/**`(surveillance) 안이다. **운영자(`OPERATOR` · `ADMIN`)만** 부른다 — 토큰 없음 401 `SECURITY_001`, 그 밖의 역할 403 `SECURITY_006`(화면 가드는 길 안내일 뿐이다). 운영자는 토큰으로만 알고 본문에 싣지 않는다. 검토 후보 목록은 아래 "운영자 검토 후보"(#266, 확정), 정정 발행 · 발행 이력 목록은 아래 "운영자 정정 · 발행 이력"(#267, 확정)이다.

```text
POST /api/v1/advisories/admin                      { districtCode, isoWeek, title, body } → Advisory                  (초안 만들기 → DRAFT)
GET  /api/v1/advisories/admin/{advisoryId}                                                → { advisory, histories }
PUT  /api/v1/advisories/admin/{advisoryId}         { title, body, memo?, version }        → Advisory                  (수정 저장 → EDITED)
POST /api/v1/advisories/admin/{advisoryId}/approve { version }                            → Advisory                  (승인 → APPROVED)
POST /api/v1/advisories/admin/{advisoryId}/publish { version }                            → Advisory                  (발행 → PUBLISHED)
POST /api/v1/advisories/admin/{advisoryId}/retract { reason, version }                    → Advisory                  (철회 → RETRACTED)
```

- `Advisory` = `{ advisoryId, districtCode, isoWeek, status, title, body, citation: { level, participantCount, symptomaticCount, respiratoryCount, entericCount, ruleVersion }, correctsAdvisoryId, publishedAt, retractedAt, version, createdAt, updatedAt }`. ID 는 문자열, `status`(`DRAFT` · `AI_DRAFTED` · `EDITED` · `APPROVED` · `PUBLISHED` · `RETRACTED`) · `level` 은 `{ code, name, description }`, 시각은 ISO-8601 UTC(초 단위)이고 값이 없는 키(`correctsAdvisoryId` · `publishedAt` · `retractedAt`)는 `null` 이다. `citation` 은 초안을 만들 때 마감 집계에서 복사해 고정한 **수**다 — 비율은 싣지 않는다.
- `histories` = `[{ historyId, fromStatus, toStatus, title, body, operatorId, aiModel, promptVersion, memo, createdAt }]` 오래된 것부터. 생성(`fromStatus: null`)부터 전이 · 수정마다 한 행이고 `title` · `body` 는 그 시점의 값, `operatorId` 는 처리한 운영자의 회원 ID 다(표시 이름은 없다). 발행 이력(A03) 타임라인의 원천이다.
- **화면 동작과 상태 전이**:
  - 초안 만들기 = `POST`. 마감됐고 `자료 부족` 이 아닌 동네 · 주 집계로만 만들고 인용 수치는 서버가 복사한다. **제목이 필수**다 — 시안 초안에는 제목 칸이 없어 연동 때 화면이 제목 입력을 더한다.
  - 수정 저장 = `PUT`(제목 · 본문 전체를 보낸다). 승인된 안내를 고치면 EDITED 로 돌아가 다시 승인해야 한다.
  - **승인하고 발행 = 두 요청**(`approve` → `publish`). 저장하지 않은 수정이 있으면 `PUT` → `approve` → `publish` 순서로 부르고, 앞 응답의 `version` 을 다음 요청에 싣는다. 발행이 `ADVISORY_007`(같은 동네 · 주에 발행 중인 안내가 있음)로 거부되면 안내는 승인됨으로 남는다.
  - **보류는 기록되지 않는다(2단계)** — 서버 처리가 없다. 후보 처리 기록(보류 · 대기 · 검토 중)은 `review_signal`(2단계)이 맡는다. 보류한 동네 · 주의 안내는 그 상태 그대로 남는다.
  - 철회 = `retract`(발행됨에서만). 동네 안내에는 철회 시각만 남는다. 정정은 `correct`(아래 "운영자 정정 · 발행 이력", #267).
- **사유 · 메모 입력란 안내: "사용자 화면에는 보이지 않아요"** — 철회 사유(`reason`)와 수정 메모(`memo`)는 운영자 상세 이력에만 있고 동네 안내(공개)에는 나오지 않는다.
- `version` 은 advisory `@Version` 이다. 처리 요청은 화면이 읽은 값을 싣고, 다르면 **409** `ADVISORY_006` 이다 — 오류 봉투에 안내가 없으니 상세(`GET`)를 다시 읽어 그린다.
- 오류: 400 — `ADVISORY_101` · `102` districtCode 없음 · 형식, `103` · `104` isoWeek 없음 · 형식, `ADVISORY_010` 형식은 맞지만 표현할 수 없는 주(53주가 없는 해의 W53, 연도 1000 미만), `105` 제목 없음 · 공백 · `106` 100자 초과, `107` 본문 없음 · 공백 · `108` 16,383자 초과, `109` 메모 500자 초과, `110` 사유 없음 · 공백 · `111` 500자 초과, `112` version 없음 · `113` 음수, `100` 본문 없음 · JSON 아님 · 타입 틀림, `198` 숫자가 아닌 ID. 404 — `001` 없는 안내, `002` 근거 집계 없음. 409 — `003` 마감 전 주, `004` 자료 부족, `005` 지금 상태에서 할 수 없는 처리, `006` 버전 다름, `007` 발행 중인 안내가 있음. 길이는 UTF-16 단위로 센다(이모지는 2자). 화면 상한(`DRAFT_MAX_LENGTH` · `REASON_MAX_LENGTH` 500자)은 서버 상한 안이다.
- **프론트 연동**: 발행 이력의 철회(`retract`)는 #271 에서 붙였다(아래 "운영자 정정 · 발행 이력" 의 "프론트 연동 (#271)"). 검토 대기의 초안 · 수정 · 승인 · 발행은 아직이다 — `features/admin/admin-review-client.ts` 는 실데이터에서 요청하지 않고 `unavailable` 이다(목 재현 그대로, 연동 #272).

## 운영자 검토 후보 `/api/v1/advisories/admin/candidates` (확정 — 백엔드 #266)

운영자 검토 대기 · 후보 상세(`/admin/review`, A01–A02)의 **후보 목록**이다. **백엔드에 구현돼 있어 확정**이다(`AdvisoryOperatorWebController#getCandidates`). 권한 · 오류 봉투는 위 "운영자 안내 처리" 와 같다 — 운영자(`OPERATOR` · `ADMIN`)만, 토큰 없음 401 `SECURITY_001`, 그 밖의 역할 403 `SECURITY_006`. 화면 동작 · 시안과 다른 점은 [SCREENS.md](design/SCREENS.md) "운영자 검토 대기 · 후보 상세" 다.

```text
GET /api/v1/advisories/admin/candidates?week=   → { isoWeek, finalized, surgeChangePercent, repeatRevisedPercent, candidates: Candidate[] }
```

- `week`(선택, `YYYY-Www`)를 생략하면 **현재 주 − 1**(마감될 수 있는 가장 최근 주 — 현재 주는 보고를 받는 중이라 초안을 만들 수 없다)이다. 조회 창은 현재 주 ~ 51주 전(동네 안내와 같다). 형식이 틀리면 `ADVISORY_104`, 미래 주 · 51주보다 이전 · 그 해에 없는 주는 `ADVISORY_009`(400). 화면 머리줄의 기준 주는 응답 `isoWeek` 로 그린다.
- `finalized` 는 그 주 집계가 마감됐는지다. 마감 전이면 어느 후보로도 초안을 만들 수 없다. `surgeChangePercent`(30) · `repeatRevisedPercent`(20)는 지금 쓰는 후보 기준이다 — 목록 설명 줄의 "후보 기준" 문구에 쓴다.
- `Candidate` = `{ districtCode, districtName, sigungu, active, signals, level, insufficientReason, draftable, participantCount, previousParticipantCount, revisedReportCount, symptomaticCount?, symptomRate?, baselineRate?, baselineDeltaPp?, groups?: [{ group, count, rate }], advisory: { advisoryId, status, version } | null }`. enum(`signals` 원소 · `level` · `insufficientReason` · `group` · `status`)은 `{ code, name, description }`, ID 는 문자열, 비율 · %p 는 퍼센트 소수 첫째 자리 반올림(행정동 주간 집계와 같다). **`?` 키는 자료 부족이면 키가 없다**(0 · null 로도 싣지 않는다). 그 밖에 값이 없는 키는 `null` 이다 — `districtName` · `sigungu` 는 행정동 행이 없을 때, `insufficientReason` 은 자료 부족이 아닐 때, `previousParticipantCount` 는 전주 집계 행이 없을 때, `advisory` 는 그 주에 안내가 없을 때.
- **신호**(`signals` — `BASELINE` → `SURGE` → `REPEAT` 순서, 저장하지 않고 조회 때 그 주 · 전주 집계로 계산한다):
  - `BASELINE` 기준선 대비 변화(`기준선 대비 변화`) — 집계 단계가 `SLIGHT` · `HIGH`. `baselineRate` 는 판정에 쓴 기준선(직전 8주 중 4주의 합산 비율 — 집계 행의 값이고 2단계 `district_baseline` 이 아니다), `baselineDeltaPp = symptomRate − baselineRate`. 표시한 `baselineDeltaPp` 가 경계 값(3 · 8)과 같아 보여도 단계는 센 수끼리의 정수 판정이 정한다 — 반올림한 두 비율의 차라 실제보다 최대 0.1 위로 보일 수 있다(예: `3.0` 인데 `NORMAL`).
  - `SURGE` 참여 급증(`참여 급증`) — 이번 주 · 전주가 모두 참여 부족이 아니고 참여자가 전주보다 `surgeChangePercent`% 이상 늘었다. 전주 행이 없으면 보지 않는다.
  - `REPEAT` 반복 보고 의심(`반복 보고 의심`) — 참여 부족이 아니고 그 주에 수정된 보고(`revisedReportCount`)가 참여자의 `repeatRevisedPercent`% 이상이다.
  - 비교는 정수라 **정확히 30% · 20% 면 후보**다. **참여 부족(LOW_SAMPLE — 참여 100명 미만) 칸은 신호가 나지 않는다.**
- **후보**는 그 주 집계 행이 있는 동네 중 신호가 하나라도 있거나, 신호가 없어도 그 주에 안내가 있는 동네다(`signals: []` — 이어서 검토하거나 발행 상태를 본다).
- **자료 부족 후보**: 참여 급변(UNSTABLE) · 기준선 없음(NO_BASELINE)이어도 급증 · 반복 신호가 있으면 후보다 — 큰 급증은 마감 판정에서 정의상 참여 급변이라 빼면 가장 큰 급증을 못 본다(2026-10-11 결정). 참여 · 전주 참여 · 수정 수만 있고 증상 수 · 비율 · 기준선 · 증상군 키가 없으며 `draftable: false` 다. 화면은 수치 · 상태색 없이 `자료 부족` 으로 그리고 초안 만들기 · 발행을 막는다.
- `draftable` 은 마감됐고 자료 부족이 아닌지다 — 초안 만들기 전제(`ADVISORY_003` · `004`)와 같다.
- `advisory` 는 그 동네 · 주의 안내 하나다 — 발행 중(PUBLISHED) → 검토 중(DRAFT · AI_DRAFTED · EDITED · APPROVED 중 가장 최근에 만든 것) → 가장 최근 철회(RETRACTED) → `null`. 제목 · 본문 · 이력은 위 "운영자 안내 처리" 의 상세(`GET /{advisoryId}`)로 읽고, 처리 요청에는 읽은 `version` 을 싣는다.
- **정렬**: 신호가 있는 후보 → 단계(`HIGH` → `SLIGHT` → `NORMAL` → `INSUFFICIENT`) → 행정동 코드. 신호 없이 안내만 있는 동네는 맨 뒤다. 화면이 첫 후보를 먼저 연다. 쪽 나누기는 없다 — 후보 수는 배열 길이, 유형별 수는 `signals` 로 센다.
- **화면 모델(`ReviewCandidate`)과 다른 점** — 매핑 계층 `features/admin/admin-review-api.ts`(#272, `toReviewCandidateList` · `toReviewCandidate` · `withAdvisoryDetail`)가 맞춘다. 정한 규칙:
  - 화면 `id` 는 `{districtCode}:{isoWeek}` 다. 대표 `kind` 는 `signals` 중 `BASELINE` → `SURGE` → `REPEAT` 순서로 하나 고르고, 원본은 `signals` 로 둔다. 신호가 없으면 `kind` 를 싣지 않는다(지어낸 유형을 붙이지 않는다).
  - PUBLISHED 후보는 목록에서 뺀다. RETRACTED 는 `대기` 로 두고 `advisoryId` · `version` 을 싣지 않는다(철회된 안내는 고칠 수 없어 초안 만들기부터다). 모르는 상태 · 단계 코드의 행은 뺀다.
  - `leadingSymptom` 은 `groups` 에서 수가 가장 큰 증상군이다. 최댓값이 같은 증상군이 둘 이상이거나 0 이면 싣지 않는다.
  - 상태: `advisory` 가 `null` · RETRACTED 면 `대기`, DRAFT~APPROVED 면 `검토 중`이다. **보류는 기록되지 않는다(2단계)** — 서버 처리가 없다(#246 결정, 후보 처리 기록은 `review_signal`). 화면의 보류는 목에서만 동작한다.
  - 초안 본문 · 제목은 상세에서 읽고, 안내가 없으면 초안 만들기(`POST`, 제목 필수)부터다. `leadingSymptom` 은 `groups` 에서 수가 큰 증상군이다(자료 부족이면 없음). `guides`(연결된 예방수칙) · `recentRates` · `burstCount` · `sameDeviceRepeatCount` · `newParticipantRate` · `reviewStartedAt` 은 응답에 없다(1단계에 출처가 없다 — 화면은 그 칸 · 행을 숨긴다). `baselineRate` · `baselineDeltaPp` 는 수치가 있는 후보에 있다.
- 상태 전이 · `version` · 409 · **승인하고 발행 = 두 요청**은 위 "운영자 안내 처리" 를 따른다 — 화면은 오류 봉투에 후보가 없으니 목록을 다시 읽어 그 후보를 바꿔 그린다(목은 충돌 응답에 최신 후보를 싣는다 — 연동 때 `GET` 을 다시 부르는 쪽으로 바꾼다).
- 정할 것(연동 이슈): 발행 전 확인 세 항목을 서버에 남길지, 검토 시작 시각의 출처(advisory `createdAt`?), 다른 주 고르기(화면은 지금 한 주).
- AI 초안 생성(DRAFT → AI_DRAFTED)은 이 화면이 부르지 않는다(범위 밖). 목 초안은 이미 있는 것으로 둔다.
- 프론트 함수: `features/admin/admin-review-client.ts` 의 `listReviewCandidates` · `saveCandidateDraft` · `holdCandidate` · `publishCandidate(…, source)`. **실데이터는 아직 요청하지 않고 `unavailable`**(화면은 "운영자 검토는 아직 준비하고 있어요")이다 — 연동 이슈 전에는 목 후보를 실제 집계로 알고 발행해도 시민에게 아무것도 나가지 않게 한다(conventions.md "데이터 출처" 의 예외). 목은 모듈 메모리 목록이고 목 세션이 비회원이 되면 지운다. 목 재현 `?mock-admin=empty|fail|conflict`.

## 운영자 정정 · 발행 이력 `/api/v1/advisories/admin/{advisoryId}/correct` · `/history` (확정 — 백엔드 #267)

운영자 발행 이력 · 정정 · 철회(`/admin/history`, A03 — 화면 #220)가 부르는 API 다. **백엔드에 구현돼 있어 확정**이다(`AdvisoryOperatorWebController#correct` · `#getPublicationHistory`). 권한 · 오류 봉투와 안내(`Advisory`) · 이력 한 행(`histories`)의 모양은 위 "운영자 안내 처리" 와 같다 — 운영자(`OPERATOR` · `ADMIN`)만, 토큰 없음 401 `SECURITY_001`, 그 밖의 역할 403 `SECURITY_006`. 철회는 위 "운영자 안내 처리" 의 `retract` 다. 화면 동작 · 시안과 다른 점은 [SCREENS.md](design/SCREENS.md) "운영자 발행 이력 · 정정 · 철회" 다.

```text
GET  /api/v1/advisories/admin/history?week=                                          → { fromIsoWeek, toIsoWeek, entries: HistoryItem[] }
POST /api/v1/advisories/admin/{advisoryId}/correct  { title, body, reason, version }  → Advisory   (정정 발행 — 새 안내, PUBLISHED)
POST /api/v1/advisories/admin/{advisoryId}/retract  { reason, version }               → Advisory   (철회 — 위 "운영자 안내 처리")
```

- **발행 이력** — `week`(선택, 끝 주 `YYYY-Www`)로 끝나는 **4주**(끝 주 − 3 ~ 끝 주 — 시안 `최근 4주`)의 발행됨(PUBLISHED) · 철회됨(RETRACTED) 안내다. 모든 동네이고 발행 시각 최근 것부터(같으면 나중에 만든 안내 — 같은 순간의 정정이면 새 안내가 먼저), 쪽 나누기는 없다. `week` 를 생략하면 끝 주 = 현재 주 − 1 이다. 형식이 틀리면 `ADVISORY_104`, 끝 주가 미래 · 51주보다 이전 · 그 해에 없는 주면 `ADVISORY_009`(400). 초안 · 수정 · 승인 단계의 안내는 없다(검토 후보 · 상세로 본다). 응답 `fromIsoWeek` · `toIsoWeek` 는 읽은 범위다.
  - `HistoryItem` = `{ advisory: Advisory, districtName, sigungu, correctedByAdvisoryId, histories }`. `advisory` 는 상세의 안내와 같다(인용 수치 `citation` · `correctsAdvisoryId` · `publishedAt` · `retractedAt` · `version` — 정정 · 철회 요청에 이 `version` 을 싣는다). `districtName` · `sigungu` 는 행정동 행이 없으면 `null` 이다. `histories` 는 그 안내의 전 이력이다(오래된 것부터 — 상세의 `histories` 와 같은 모양, 운영자 ID · 사유 `memo` 포함).
  - **정정 관계는 양방향**이다. `advisory.correctsAdvisoryId` 는 이 안내가 정정한 이전 안내, `correctedByAdvisoryId` 는 이 안내를 정정한 새 안내(서버가 조회 때 만든다)다. RETRACTED 인데 `correctedByAdvisoryId` 가 있으면 `정정됨`, 없으면 `철회` 다. 정정은 같은 동네 · 주로 만들어 두 안내는 늘 같은 목록에 있다.
- **정정 발행** — 발행 중인 안내를 철회(사유 = 이력 메모)하고 고친 `title` · `body` 로 새 안내를 발행해 `correctsAdvisoryId` 로 잇는다. **한 요청 · 한 트랜잭션**이라 동네 안내에 "철회된 안내" 가 끼지 않고, 새 안내와 그 정정 시각(`corrections[].correctedAt` = 새 안내의 `publishedAt` = 이전 안내의 `retractedAt`)이 보인다. 응답은 새 안내(`status` PUBLISHED, `version` 1, `correctsAdvisoryId` = 요청한 안내)다.
  - **정정 요청 자체가 승인이다** — 새 안내는 초안 · 수정 · 승인 단계를 따로 거치지 않고 이력이 `(없음) → APPROVED` · `APPROVED → PUBLISHED` 두 행이다. 이전 안내에는 `PUBLISHED → RETRACTED` 한 행이 더해진다. 사유는 이전 안내의 철회 행과 새 안내의 생성 행 `memo` 에 함께 남는다.
  - **인용값은 이전 안내에서 옮긴다**(근거 집계 · 동네 · 주 · `citation`) — 집계가 다시 계산돼도 운영자가 검토한 같은 근거다. 본문의 숫자는 검증하지 않는다(본문은 운영자가 승인한 문장이다). 본문을 바꾸지 않고 사유만 남기는 정정도 받는다.
  - **`title` 은 필수**다(새 안내의 전체 값). 시안 대화상자에는 제목 칸이 없다 — #271 에서 지금 제목을 처음 값으로 둔 `제목` 칸(100자, 비면 막음)을 더했다.
  - 오류: 400 — `105` 제목 없음 · 공백 · `106` 100자 초과, `107` 본문 없음 · 공백 · `108` 16,383자 초과, `110` 사유 없음 · 공백 · `111` 500자 초과, `112` version 없음 · `113` 음수, `100` 본문 없음 · JSON 아님 · 타입 틀림, `198` 숫자가 아닌 ID. 404 — `001`. 409 — `006` 버전 다름, `005` 발행 중이 아님(정정됨 · 철회 · 검토 단계), `007` 같은 동네 · 주에 다른 발행 중인 안내가 있음(규칙 위반 데이터). 거부되면 아무것도 바뀌지 않는다 — 화면은 이력을 다시 읽어 그린다(실데이터는 `GET /history` 를 다시 부른다, 목은 충돌 응답에 이력을 바로 싣는다). 화면 상한(본문 500자 · 사유 `REASON_MAX_LENGTH` 500자)은 서버 상한 안이다.
- **사유 입력란 안내: "사용자 화면에는 보이지 않아요"** — 정정 · 철회 사유는 운영자 응답(이력 `memo`)에만 있고 동네 안내(공개)에는 나오지 않는다(#246 · #267 결정). A03 의 정정 대화상자 · 결과 문구는 "사용자 화면에 정정일이 함께 보여요" 다(#271 — 목 · 실데이터 같음).
- **화면 모델(`HistoryEntry`, `features/admin/types.ts`)과 다른 점** — 연동 때 매핑 계층이 맞춘다:
  - 한 행이 **안내 하나**다. 보류한 후보 행(`outcome: held`)은 서버에 없다(보류는 기록되지 않는다 — 2단계 `review_signal`).
  - `outcome`: PUBLISHED → `published`, RETRACTED 이고 `correctedByAdvisoryId` 가 있음 → `corrected`, 그 밖의 RETRACTED → `retracted`. 정정한 새 안내의 `correction` = `{ previousId: correctsAdvisoryId, correctedOn: publishedAt 의 한국 날짜, reason: 생성 행(fromStatus null)의 memo }`, `retraction` = `{ retractedOn: retractedAt 의 한국 날짜, reason: RETRACTED 행의 memo }`.
  - `timeline` 은 `histories` 를 옮긴다 — 생성(null → DRAFT) `registered`, AI_DRAFTED `ai-drafted`, EDITED `edited`, PUBLISHED `published`(승인은 묶음 — 정정한 새 안내는 생성 행과 묶어 `correction-published`), RETRACTED 는 정정됨이면 `corrected` · 아니면 `retracted`. `at` = `createdAt`, `note` 는 정정 · 철회 `memo` 만이다.
  - `participants` = `citation.participantCount`, `symptomRate` 는 `citation` 의 수로 계산한다(서버는 비율을 싣지 않는다 — 공개 집계와 같은 반올림). `body` · `version` 은 `advisory` 의 값, `processedAt` = `publishedAt`, `edited` = PUBLISHED 전에 EDITED 이력이 있는지.
  - `kind`(후보 유형 baseline · surge · repeat)는 응답에 없다 — 신호는 검토 후보 조회 때 계산하고 안내에 남기지 않는다(#271 에서 화면 모델의 선택 값으로 바꾸고, 값이 없으면 유형 열 · 근거 줄의 유형을 숨긴다). `operatorName` · `reviewMinutes` 도 없다(`operatorId` 뿐, 검토 시작 시각을 저장하지 않는다 — 화면은 그 열 · 칸을 숨긴다).
- **프론트 연동 (#271)**: `features/admin/admin-history-client.ts` 의 `listHistory` · `correctAdvisory` · `retractAdvisory(…, source)`. 응답 모양과 위 "화면 모델과 다른 점" 의 매핑은 `features/admin/admin-history-api.ts`(`toHistoryEntry`)다. 목은 모듈 메모리 이력이고 검토 대기 목의 보류 · 발행이 더해진다(`recordReviewOutcome`). 목 세션이 비회원이 되면 지운다. 목 재현 `?mock-admin=empty|fail|conflict` 는 목데이터에서만 듣는다.
  - **목록**: `GET /history`(`week` 생략 — 현재 주 − 1 로 끝나는 4주). 실패하면 거부해 화면이 빨강 상자 + `다시 시도` 를 보인다. 상태가 발행됨 · 철회됨이 아니거나 인용 수로 비율을 낼 수 없는(참여 0) 항목은 그 행만 뺀다 — 한 행 때문에 화면 전체를 막지 않는다. `districtName` 이 null 이면 행정동 코드로 보인다. `symptomRate` 는 `symptomaticCount / participantCount` 를 퍼센트 소수 첫째 자리 HALF_UP 으로 계산한다(`citedRate` — backend `AggregateRate` 와 같다). 날짜(`correctedOn` · `retractedOn`)는 KST, 시각이 없으면 빈 날짜다. 응답의 `fromIsoWeek` · `toIsoWeek` · `sigungu` · `operatorId` 는 쓰지 않는다.
  - **정정** `POST /{id}/correct { title, body, reason, version }` · **철회** `POST /{id}/retract { reason, version }`. 성공하면 `GET /history` 를 다시 읽고 응답 안내의 `advisoryId`(정정은 새 안내, 철회는 그 안내)를 고른다. 409 `ADVISORY_005` · `006` · `007` 과 404 `ADVISORY_001` 은 아무것도 바뀌지 않은 거부라 다시 읽어 충돌로 그린다. 검증 400(`ADVISORY_100` · `105`~`113` · `198`)은 다시 읽지 않고 대화상자 안에 "입력한 내용을 확인해 주세요" 다(화면이 먼저 막아 보통은 닿지 않는다). 그 밖(일시 장애 · 분류 밖 · `002`)은 거부해 대화상자 안 "잠시 뒤 다시 시도해 주세요" 다.
  - **처리 뒤 다시 읽기가 실패하면**(`unrefreshed`) 처리 결과(또는 충돌)는 알리고 목록은 실패 상태(`다시 시도`)로 둔다 — 옛 목록을 두면 이미 정정 · 철회한 안내의 버튼이 남는다. 다시 읽으면 처리한 안내를 고른다.
  - **401 · 403**: 401 은 API 계층(재발급 · 만료 알림)이 다룬다. 403(`SECURITY_006`)은 거부와 함께 세션 요약을 다시 맞춘다(`refreshSession` — 역할이 바뀌었으면 운영자 가드가 권한 없음을 그린다). 화면 갈래는 일시 장애와 같다.
  - **보류**는 서버에 없어 실데이터 화면은 요약의 `보류` 칸을 두지 않고, 부제는 "최근 4주 동안 발행한 안내의 승인·수정·정정·철회 기록이 남아요.", 빈 목록은 "최근 4주 동안 발행한 안내가 없어요." 다. 후보 유형 · 담당 · 검토 시간 열은 값이 없어 숨는다.
