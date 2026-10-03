# API 계약

백엔드(origin/develop)와 맞춘 계약이다. **인증 · 회원 · 행정동 · 주간 보고는 백엔드에 구현돼 있어 확정**이고, 집계 · 안내 · 공식 정보 · 운영자는 **BE 미정**이라 아래 초안은 프론트 제안이다.

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
  - 동의(건강정보 · 재동의) · 탈퇴 함수는 아직 출처와 무관하게 목이다. 내 정보 · 내 동네는 아래 "회원" 의 "프론트 연동 (#164)", 비밀번호 재설정 · 로그인한 기기는 바로 아래 "프론트 연동 (#166)", 카카오는 아래 "프론트 연동 (#167)" 이다.
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
- **BE 미정**: 건강정보 동의 · 철회 · 약관 재동의 · 탈퇴(#59). 프론트 `agreeHealthConsent` · `withdrawHealthConsent` · `agreeTermsReconsent` · `withdrawMembership` 은 목이다.

## 회원 `/api/v1/members` (확정)

모두 인증 필요(`Authorization: Bearer`).

| 요청                | 바디 → `dataBody`                                                 | 주요 오류                                                                                                                     | 프론트 함수                       |
| ------------------- | ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| `GET /me`           | → `MyInfo`                                                        | `MEMBER_004` 회원 없음(404, 로그아웃 상태로), `002` · `003`(403)                                                              | `fetchMyInfo`(회원 정보 저장소)   |
| `PATCH /me`         | `{ nickname }` → `MyInfo`                                         | 검증 `MEMBER_101` · `102`                                                                                                     | (연동 이슈)                       |
| `POST /me/password` | `{ currentPassword, newPassword }` → null (다른 기기 로그아웃)    | `MEMBER_005` 불일치(400) · `006` 잠금(429) · `007` 비밀번호 없음(409) · `009`(503), 검증 `103~107`                            | `changePassword`                  |
| `GET /me/region`    | → `MemberRegion` \| null (아직 고르지 않음)                       | `REGION_004` 행정동 확인 장애(503)                                                                                            | `fetchMyRegion`(회원 정보 저장소) |
| `PUT /me/region`    | `{ code }`(숫자 8자리) → `MemberRegion` (`abolished` 는 늘 false) | `REGION_001` 없는 코드 · `002` 폐지(400), `003` 동시 첫 저장 경합(409, 다시 보내면 갱신), `004`(503), 검증 `101` · `102`(400) | `saveRegion`                      |

- `MyInfo` = `{ memberId, email, nickname, provider(EMAIL | KAKAO), hasPassword, role, pendingConsents, reportWritable }`. `provider` 는 카카오를 연결한 이메일 계정도 `KAKAO` 다(DB 값이 없으면 `EMAIL`). `hasPassword` 가 true 면 비밀번호 변경을 보이고, false 면(카카오로만 로그인) **비밀번호 메뉴를 숨긴다** — 비밀번호 최초 설정 API(`POST /me/password/setup`)는 백엔드 #61 에서 없앴고(`MEMBER_008` 은 비운 번호), 프론트도 #166 에서 `setupPassword` 와 설정 화면을 지웠다(목도 같은 동작). 재동의 조건 `pendingConsents` 는 로그인 응답과 같은 계산이다.
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
- **BE 미정**: 프로필 이미지(#112).

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

본인의 **이번 주** 보고 하나다. 모두 인증 필요(`Authorization: Bearer`) **+ 건강정보 동의(scope `report:write`)** — 건강 · 증상은 민감정보라 조회도 쓰기와 같은 권한이다. 경로 · 본문에 주를 받지 않는다(지난 주 보고는 쓰지도 읽지도 못한다). 응답에 회원 ID · 보고자 키 · 보고 ID 를 싣지 않는다.

| 요청     | 바디 → `dataBody`                                                                                     | 주요 오류                                                                                                                                                                                                                    | 프론트 함수                               |
| -------- | ----------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| `PUT`    | `{ districtCode, symptomGroups }` → `WeeklyReport` (이번 주에 처음이면 저장, 있으면 동네 · 증상 바꿈) | `REPORT_002` 없는 행정동 · `003` 폐지된 행정동(400), `001` 같은 주 동시 처리(409 — 서버가 한 번 다시 하고도 졌다), 검증 `REPORT_100`(모르는 증상군 · 깨진 JSON) · `101`~`105`(동네 없음 · 형식 · 목록 없음 · 빈 원소 · 중복) | `submitReport` · `updateReport`           |
| `GET`    | → `WeeklyReport` \| null (아직 보내지 않음)                                                           |                                                                                                                                                                                                                              | `fetchCurrentReport`(이번 주 보고 저장소) |
| `DELETE` | → null (멱등 — 없어도 200, 이번 주 집계에서 빠짐)                                                     |                                                                                                                                                                                                                              | `cancelReport`                            |

- 공통 오류: 토큰 없음 `SECURITY_001`(401), **동의 전(scope 없음) `SECURITY_006`(403)** — 서비스(security-core `CustomAccessDeniedHandler`)가 낸다. 게이트웨이는 토큰이 없으면 통과시키고 인가 판정을 하지 않는다(`JwtErrorCode` 주석).
- 요청 `districtCode` 는 SGIS 숫자 8자리, `symptomGroups` 는 `RESPIRATORY`(호흡기: 발열 · 기침 · 인후통) · `ENTERIC`(장관: 구토 · 설사) 목록이다. **증상 없음은 빈 배열**이고 정상 보고다(건강한 보고가 집계의 분모). null · null 원소 · 중복은 거부된다.
- `WeeklyReport` = `{ isoWeek, districtCode, symptomGroups: [{ code, name, description }], reportedAt, updatedAt }`. `isoWeek` 는 `2026-W40` 처럼 KST 달력 기준 ISO 주(월요일 시작)이고 서버가 정한다. `symptomGroups` 는 선언 순서(호흡기 → 장관). `reportedAt` 은 이번 주 첫 보고 시각, `updatedAt` 은 마지막 수정 시각(ISO-8601 UTC, 초 단위 — 고친 적이 없으면 같다).
- **보고 주는 서버가 받은 시각(KST)으로 정한다**(`ReportWeekCalculator` · `ReportWeek`). 일요일 23:59 KST 에 흐름을 열어 월요일 00:00 KST 뒤에 보내면 새 주로 저장된다. 프론트는 주를 계산하지 않고, 응답 `isoWeek` 가 정본이다.
  - 화면에 보이는 주 라벨(`ReportWeek` 의 `weekRangeLabel` · `reportPeriodLabel`)은 아직 홈 주간 집계 목(`features/home/mock.ts`, BE 미정)의 예시 값(`11월 17일(월)~23일(일)`)이라 **서버 `isoWeek` 와 무관하다** — 지금은 늘 어긋날 수 있다. 주간 집계 API 연동 때 응답 주로 바꾸고, 그 뒤에도 일요일 자정 근처에는 연 시점의 라벨과 저장된 주가 다를 수 있다(받은 보고의 `isoWeek` 로 고칠지는 그때 정한다).
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

## BE 미정 — 프론트 초안

백엔드에 아직 없다(게이트웨이 라우트 `/api/v1/advisories/**` 는 열려 있다). 아래는 화면을 만들며 가정한 모양이고, 연동 이슈에서 백엔드와 맞춘다. 지역 키 `admCd` 는 행정동 `code`(SGIS 8자리)다.

```text
GET  /api/regions/{admCd}/weekly?week=     → { status: normal|slight|high|insufficient,
                                                participants, publicThreshold,
                                                symptomRate?, baselineRate?,   // insufficient면 없음
                                                groups: [{ key, trend, series: number[] }] }
GET  /api/notices/{admCd}?week=            → 발행된 안내 | null (정정·철회 이력 포함)
GET  /api/official/latest                  → 질병관리청 단계·기준 주·요약·원문 링크
GET  /api/admin/candidates?week=           → 후보 목록 (운영자)
POST /api/admin/candidates/{id}/publish|hold|correct|withdraw
```

- `insufficient` 응답에는 `symptomRate` · `baselineRate` 가 없다. 프론트는 이 값이 없을 때 수치·상태색을 그리지 않는다.
- 프론트 함수: 안내 `notice-client.ts`(`getRegionNotice`). 홈 주간 집계 · 공식 정보 · 운영자는 목 데이터다(보고는 위 "주간 보고" 로 확정).
