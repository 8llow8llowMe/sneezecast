# API 계약 초안

디자인 핸드오프와 함께 받은 **프론트엔드 쪽 초안**이다. 백엔드와 맞추기 전이므로 확정 계약이 아니다.
연동 전에 게이트웨이 Swagger(`https://api-dev.sneezecast.com`)와 대조하고, 확정되면 이 문서를 실제 계약으로 고친다.

```text
GET  /api/v1/districts?query=○○동         → 행정동 검색 [{ code, name, sigungu }]
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

- 행정동 검색의 경로 · 필드 이름(`code` · `name` · `sigungu`)은 프론트 목(`features/region`) 기준이다. 확정은 백엔드 #60 과 맞춘다.
- 로그인한 회원만 보고한다(카카오 · 이메일, 백엔드 #56~#61). 보고 데이터에는 회원 식별자 대신 가명 키(`reporter_key`)만 남는다.
- `insufficient` 응답에는 `symptomRate` · `baselineRate` 가 없다. 프론트는 이 값이 없을 때 수치·상태색을 그리지 않는다.
- 백엔드 설계 문서: [`backend/docs/entity-design.md`](../../backend/docs/entity-design.md), [`backend/docs/architecture-guide.md`](../../backend/docs/architecture-guide.md)

## 비밀번호 재설정 (백엔드 #58 과 맞출 제안)

백엔드는 경로만 정해져 있다(`POST /api/v1/auth/password/reset/send-code` · `POST /api/v1/auth/password/reset`, `backend/docs/modules.md`).
시안(S13-6)은 이메일 → 인증 코드 → 새 비밀번호로 코드 확인 단계를 따로 두므로, **프론트는 아래 제안 1을 가정해 목으로 만들었다** (`features/auth/auth-client.ts` 의 `sendPasswordResetCode` · `verifyPasswordResetCode` · `resetPassword`).

```text
POST /api/v1/auth/password/reset/send-code    { email }                     → 200 (가입 여부와 무관하게 같은 응답) | 발송 제한
POST /api/v1/auth/password/reset/verify-code  { email, code }               → 200 { resetToken } (일회용 · 수명 15분, 응답 본문)
                                                                               | 틀림 · 만료 · 잠김 (가입 AUTH_003 · 004 · 005 와 같은 뜻)
POST /api/v1/auth/password/reset              { resetToken, newPassword }   → 200 (토큰을 소비한다)
                                                                               | 인증 만료(토큰 없음 · 지남 · 이미 씀)
```

- 제안 1 (프론트가 가정한 방식): **verify-code 가 일회용 재설정 토큰을 응답 본문으로 주고, `reset{resetToken, newPassword}` 가 그 토큰을 소비한다.** 가입과 같은 단계라 화면 · 클라이언트 코드를 같이 쓸 수 있고, 코드가 틀렸는지 새 비밀번호를 쓰기 전에 알 수 있다. 토큰은 추측할 수 없는 값으로 서버(Redis)에 이메일과 함께 두고, 성공하면 지운다. 화면은 토큰을 메모리에만 들고 주소 · 로그 · 브라우저 저장소에 남기지 않는다.
- **이메일만으로 재설정하는 토큰 없는 방식은 쓰지 않는다.** 가입처럼 verify-code 가 이메일별 인증 표시만 남기고 `reset{email, newPassword}` 를 받으면, 인증 표시가 살아 있는 동안 코드를 모르고 이메일만 아는 사람이 비밀번호를 바꿀 수 있다. 재설정 권한은 코드를 맞힌 쪽이 받은 토큰에 묶는다.
- 제안 2 (대안): verify-code 없이 `POST /password/reset` 이 `{ email, code, newPassword }` 를 한 번에 받는다. 이 경우 화면은 코드 단계에서 서버에 묻지 않고 코드를 들고 새 비밀번호 단계로 가며, 틀림 · 만료 · 잠김을 새 비밀번호 단계에서 받아 코드 단계로 돌려보내야 한다. 정해지면 연동 이슈에서 `auth-client.ts` 와 코드 · 새 비밀번호 화면을 함께 고친다.
- 두 방식 모두 가정하는 것: 코드 받기 · 확인의 응답이 가입 여부와 무관하게 같다(계정 열거 방지). 코드 한도 · 수명은 가입 인증과 같다(6자리 · 5분 · 다시 받기 60초 · 오입력 5회). 발송 제한은 남은 시간을 주지 않는다. 남은 시도 횟수를 주지 않으면 가입처럼 `auth-client` 가 이메일별 실패 수를 센다. 새 비밀번호 규칙은 가입과 같다(8~20자 · 영문과 숫자 · 공백 금지). 재설정 코드 · 토큰은 가입 인증과 저장소가 따로다.
- `POST /password/reset` 에도 시도 상한을 둔다: IP당 시도 상한(가입 검증의 IP 상한과 같은 방식), 토큰당 시도 상한(넘으면 토큰을 지운다). 제안 2면 이메일당 코드 오입력 상한이 같은 역할을 한다.
- 재설정 뒤 그 회원의 기존 세션(refresh 토큰 · 기기 세션)을 모두 폐기하는 것을 검토한다 — 비밀번호를 잃어 재설정하는 경우가 많아서다. 화면은 재설정 뒤 이메일 로그인으로 보낸다.
