# 화면 대응표

| ID | 화면 | 모바일 | 태블릿 | 데스크톱 | 제안 라우트 |
| --- | --- | --- | --- | --- | --- |
| S01 | 시작 | Start | Start-T | Start-D | `/start` |
| S13-1 | 로그인 방법 고르기 (auth 캔버스) | Login, Login-kakao-fail, Login-kakao-exists | -T | -D | `/login` |
| S13-2 | 이메일 가입 · 이메일 (auth 캔버스) | Signup-email | -T | -D | `/signup/email` |
| S13-3 | 이메일 가입 · 인증 코드 (auth 캔버스) | Signup-code | -T | -D | `/signup/code` |
| S13-4 | 이메일 가입 · 비밀번호 · 닉네임 (auth 캔버스) | Signup-account | -T | -D | `/signup/account` |
| S13-5 | 이메일 로그인 (auth 캔버스) | Login-email | -T | -D | `/login/email` |
| S13-6 | 비밀번호 재설정 · 이메일 · 인증 코드 · 새 비밀번호 (auth 캔버스) | Signup-email · Signup-code 재사용, Password-reset | -T | -D | `/password/reset` · `/password/reset/code` · `/password/reset/new` |
| S02-1 | 동네 선택 | Setup-1 | Setup-1-T | Setup-1-D | `/setup/region` |
| S02-2 | 성인 확인 | Setup-2 | Setup-2-T | Setup-2-D | `/setup/adult` |
| S02-3 | 가입 동의 (3 / 4, auth 캔버스) | Setup-3 | Setup-3-T | Setup-3-D | `/setup/terms` |
| S02-4 | 증상 보고 동의 (4 / 4, auth 캔버스) | Setup-4 | Setup-4-T | Setup-4-D | `/setup/health-consent` |
| S03 | 홈 (평소 수준·조금·많이·자료 부족) | Home-good, Home-normal, Home, Home-pending | Tablet, Home-nodata-T | Desktop, Home-nodata-D | `/` |
| S03 | 둘러보기 홈 (비로그인, auth 캔버스) | Home-guest | Home-guest-T | Home-guest-D | `/` (비회원) |
| S03 | 로그인 안내 시트 (auth 캔버스) | Login-sheet | Login-sheet-T | Login-sheet-D | 홈 위 모달 `/?report=login` |
| S02-4 | 증상 보고 동의 시트 (auth 캔버스) | Consent-health-sheet | -T | -D | 홈 위 모달 `/?report=health-consent` |
| S04 | 지도 | Map-collapsed, Map-expanded, Map-nodata | Map-expanded-T, Map-nodata-T | Map-expanded-D, Map-nodata-D | `/map?region=` |
| S05 | 주간 건강 보고 (시트·대화상자) | Report-start, Report-symptom, Report-confirm, Report-edit | 같은 이름 + -T | 같은 이름 + -D | 홈 위 모달 `/?report=start` |
| S06 | 보고 완료 · 되돌리기 | Report-done, Report-done-ok | -T | -D | 모달 |
| S07 | 동네 안내 (발행·없음·정정) | Guide-published, Guide-none, Guide-corrected | -T | -D | `/notice/[region]/[week]` |
| S08 | 공식 정보 | Official | Official-T | Official-D | `/official` |
| S10 | 내 정보 (기본·알림 미지원) | Settings, Settings-nopush | -T | -D | `/me` |
| S11 | 판단 기준 설명 | Explain | Explain-T | Explain-D | 홈 위 모달 `/?explain=1` |
| S12 | 홈 화면 추가 안내 | Install | Install-T | Install-D | 모달 또는 `/install` |
| 상태 | 불러오는 중·오류·오프라인·알림 대신 홈 표시 | State-loading, State-error, State-offline, State-push-inapp | -T | -D | 공통 |
| A01–A02 | 운영자 검토 대기·후보 상세 | — | — | Admin | `/admin/review` |
| A03 | 발행 이력·정정·철회 | — | — | History | `/admin/history` |
| 시연 | 클릭 프로토타입 | Flow | — | — | (참고용) |

S09 학부모 그룹은 이번 범위가 아니라 만들지 않습니다.

## 첫 진입 (S01 · S02 · S13) 메모

- 단계 표시는 `n / 4` 입니다. 시안은 `/ 3` 이지만 S02-4 증상 보고 동의가 더해질 예정입니다.
- 둘러보기 변형: S01 `보고 없이 둘러보기` → `/browse/region` (Setup-1-browse). 단계 표시가 없고 버튼이 `이 동네 보기` 이며, 고르면 `/?region=<행정동 코드>` 홈으로 갑니다.
- S02-1 동네 선택 · 결과 없음 · 둘러보기와 S02-2 성인 확인은 새 캔버스 `auth/screens/` 의 Setup-1 · Setup-1-empty · Setup-1-browse · Setup-2 가 정본입니다. 이 폴더의 `screens/Setup-1*` · `Setup-2*` 와 다르면 새 쪽을 따릅니다.
- 흐름: S01 `시작하기` → `/login`(S13-1) → 카카오 신규 회원이면 `/setup/region?from=kakao`. 로그인 화면은 단계 표시가 없습니다.
- `/login` 상태는 주소 쿼리로 받습니다: `?error=kakao-fail` · `?error=kakao-exists` · `?reason=expired`. 연동 때 카카오 콜백 · 세션 만료가 같은 쿼리로 돌려보냅니다. `/login/email?reason=reset-done` 은 비밀번호를 바꾼 뒤입니다.
- 이메일 로그인 목(`features/auth/auth-client.ts`)에서 상태를 재현하는 입력: 이메일 `locked@example.com` → 잠김(locked), 비밀번호 `wrong` → 맞지 않음(wrong), 그 밖 → 성공(홈으로).
- 이메일 가입 흐름: `/login` 이메일로 가입하기 → `/signup/email` → `/signup/code` → `/signup/account` → `/setup/region`. 단계 표시는 없습니다. 입력값은 메모리(Provider)에만 두고, 중간 단계에 바로 들어오면 `/signup/email` 로 돌려보냅니다.
- 백엔드 #56 화면 계약(`backend/docs/modules.md` "화면 계약")에 맞춰 시안과 다르게 둔 것:
  - S13-2 에 "이미 가입된 이메일"(exists) 상태가 없습니다. 코드 받기는 가입 여부와 무관하게 같은 응답이라 늘 `/signup/code` 로 가고, S13-3 제목 아래에 "이미 가입한 이메일이면 코드 대신 안내 메일이 가요." 를 늘 보입니다.
  - 코드 요청 제한(limit)은 남은 시간을 주지 않아 "조금 뒤 다시 시도해 주세요" 로 씁니다(시안 "10분 뒤").
  - 코드 확인은 5번까지 틀릴 수 있어 첫 실패 문구가 "남은 시도는 4번이에요" 입니다(시안 장면은 3번).
  - 백엔드는 남은 시도 횟수를 주지 않습니다(틀림 `AUTH_003`, 5번째 `AUTH_005` 잠김 + 코드 삭제, 그다음 확인은 `AUTH_004` 만료). 연동 때 `auth-client.ts` 가 이메일별 실패 수를 세어 남은 시도를 채우고, 코드를 다시 받으면(sent) 0 으로 되돌립니다. 목도 잠기면 코드를 지워 그다음 확인이 expired 입니다.
  - 비밀번호는 8~20자 · 영문과 숫자 함께 · 공백 없이입니다. 도움말 "8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이", 오류 "영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요." (시안 "8자 이상 · 영문과 숫자 포함" · "8자 이상으로 영문과 숫자를 함께 써 주세요.")
- 가입 목(`features/auth/auth-client.ts`)에서 상태를 재현하는 입력:
  - 코드 받기: 이메일 `limit@example.com` → 요청 많음(limit), 형식이 틀리면 invalid(보낼 때 판단). 그 밖은 이미 가입한 이메일이어도 보냄
  - 코드 확인: `000000` → 틀림(남은 시도 4번부터 1씩 줄고 5번째에 locked), `999999` → 잠김(locked), 잠긴 뒤 · 5분이 지나면 expired, 그 밖 6자리 → 성공
  - 비밀번호 · 닉네임: 8자 미만 · 20자 초과이거나 영문 · 숫자 중 하나가 없거나 공백이 있으면 pw-rule, 확인이 다르면 pw-mismatch, 닉네임 2~10자 밖이면 nick-long(다음을 누를 때 판단)
- 가입 마무리: 성인 확인 `다음` → `/setup/terms`(S02-3) → 가입 요청 → (이메일 가입만) 로그인 → 내 동네 저장 → `/setup/health-consent`(S02-4, 기록을 바꿔 감) → `동의하고 시작하기` 또는 `나중에 할게요` → 홈 `/`(기록을 바꿔 감).
  - 가입 응답에 토큰이 없어 이메일 가입은 가입 뒤 이메일 · 비밀번호로 로그인합니다. 카카오 가입은 이미 로그인한 상태라 바로 동네를 저장합니다.
  - 가입 종류는 Provider 초안의 `method` 로 가립니다: 이메일 화면이 코드를 보내면 `email`, 로그인 화면에서 카카오로 시작하거나 S02-1 이 `?from=kakao` 로 열리면 `kakao`, `/login` 에 들어오면 지웁니다. 모르면 S02-3 이 `/login` 으로, 이메일 가입인데 인증 · 비밀번호가 없으면 `/signup/email` 로 돌려보냅니다.
  - 가입 · 로그인 · 동네 저장 중 하나가 실패하면 다시 누를 때 끝난 단계는 보내지 않습니다(가입 두 번 금지). 응답을 기다리는 동안 화면을 떠나도 서버에서 끝난 단계는 Provider 에 남깁니다. 비밀번호는 로그인을 마친 뒤 지웁니다.
  - 이 진행은 가입 시도 하나에만 딸립니다. `/login` 에 들어오거나 카카오로 시작하면(`resetSignup`), 이메일 화면이 코드를 보내면 비웁니다.
  - **연동 요구사항**: 카카오 신규 회원 콜백은 `?from=kakao` 로 S02-1(`/setup/region?from=kakao`, `paths.ts` 의 `SETUP_REGION_FROM_KAKAO_PATH`)에 돌아옵니다. 실제 카카오 로그인은 페이지를 새로 열어 메모리가 비므로, S02-1 이 이 표시를 보고 가입 종류가 카카오가 아니면 가입 초안 · 마무리 진행을 비우고(`resetSignup`) `method: 'kakao'` 로 둡니다. 이미 카카오면(S13-1 에서 시작 · 뒤로 가기로 다시 옴) 진행 중인 가입을 지우지 않습니다. 목 `startKakaoLogin` 도 같은 주소를 돌려줘 홈의 로그인 안내 시트(첫 진입 Provider 밖)에서 시작해도 S02-3 까지 카카오 가입으로 이어집니다.
  - 인증을 마친 지 30분이 지나 가입이 인증 만료(`AUTH_007`)로 돌아오면 인증 · 비밀번호를 지우고 `/signup/email?reason=verification-expired` 로 갑니다. 이메일 화면은 "인증 시간이 지났어요. 이메일 인증부터 다시 해 주세요." 를 띄우고, 다시 인증하면 닉네임은 남아 있습니다.
  - 전체 동의는 필수 둘이 켜졌는지를 따릅니다(시안 default 는 선택이 꺼져도 전체 동의가 켜짐). 켜면 선택까지 모두 켜고 끄면 모두 끕니다.
  - 약관 "보기" 는 본문이 아직 없어 "약관 본문을 준비하고 있어요" 알림만 띄웁니다.
  - S02-4 는 시안에 뒤로 버튼이 있지만 가입을 마친 뒤라 숨깁니다(가입 동의로 돌아가 다시 가입하지 않게).
  - 동의 문서 버전은 `features/auth/legal.ts` 가 정본이고 백엔드 `legal.*-version` 을 맞춥니다.
- 가입 목 재현 입력: 이메일 `signup-fail@example.com` 으로 가입하면 가입 요청이 거부되고, `verify-expired@example.com` 이면 늘 인증 만료입니다. `locked@example.com` 으로 가입하면 가입은 되고 이어지는 로그인이 잠김으로 실패합니다(로그인 목과 같은 입력). 이 입력은 다시 눌러도 계속 실패합니다(로그인 재시도가 성공하는 흐름은 자동 테스트가 덮습니다).
- 비밀번호 재설정 흐름(S13-6): `/login/email` 의 `비밀번호를 잊었어요` → `/password/reset`(이메일) → `/password/reset/code` → `/password/reset/new` → `/login/email?reason=reset-done`(기록을 바꿔 감). 단계 표시는 없습니다.
  - 이메일 · 코드 단계는 시안 안내(`auth/README.md`)대로 가입 화면을 재사용합니다. 두 흐름이 `features/auth/email-step.tsx` · `code-step.tsx` 를 같이 쓰고 문구 · 보낼 · 확인할 함수 · 인증 결과를 남기는 법(가입은 인증 시각, 재설정은 일회용 토큰) · 앞뒤 경로만 다르게 넘깁니다. 한도 · 상태(invalid · limit · wrong · expired · locked · 인증 마침)는 가입과 같습니다.
  - 시안에 재설정용 원문이 없어 가입 문구에서 바꾼 것: 이메일 단계 제목 "가입한 이메일을 알려 주세요"(가입 "이메일을 알려 주세요"), 코드 단계 제목 아래 중립 문구 "가입한 이메일이면 코드를 보내 드려요."(가입 "이미 가입한 이메일이면 코드 대신 안내 메일이 가요."). 버튼("인증 코드 받기" · "확인") · 도움말 · 오류 · 오른쪽 패널 문구는 가입과 같습니다.
  - 가입 여부를 드러내지 않습니다. 코드 받기는 가입 여부와 무관하게 같은 응답이라고 가정해 늘 코드 단계로 가고, "가입하지 않은 이메일" 상태가 없습니다.
  - 새 비밀번호(Password-reset default · mismatch · rule): 규칙 · 문구는 S13-4 와 같습니다 — 도움말 "8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이", 오류 "영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요." · "비밀번호가 서로 달라요." (시안 "8자 이상 · 영문과 숫자 포함" · "8자 이상으로 영문과 숫자를 함께 써 주세요."). 오류는 시안 rule 장면(확인 칸이 빈 채 오류)과 달리 `비밀번호 바꾸기` 를 누를 때 처음 보입니다(S13-4 와 같은 규칙, 빈 칸이 있으면 버튼이 꺼져 있음). 보내지 못하면 빨강 상자 "비밀번호를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요." 는 시안에 없어 더했습니다.
  - 코드를 맞히면 서버가 **일회용 재설정 토큰**(15분)을 주고, 새 비밀번호 요청은 이메일이 아니라 이 토큰으로 보냅니다(토큰 없이 이메일만으로 재설정하지 않습니다 — `docs/api-contract-draft.md`).
  - 값은 Provider 의 재설정 초안(`passwordReset`: 이메일 · 보낸 시각 · 재설정 토큰)에만 두고 가입 초안과 섞지 않습니다. 토큰은 주소 · 로그 · 브라우저 저장소에 남기지 않습니다. 새 비밀번호는 그 화면 상태에만 둡니다. 중간 단계에 바로 들어오면(값이 없음, 새 비밀번호 단계는 토큰이 없음) `/password/reset` 으로 돌려보냅니다. 가입 초안의 인증으로는 열리지 않습니다.
  - 새 비밀번호를 보내는 중에는 버튼 · 칸과 함께 머리줄 뒤로도 꺼 둡니다(`aria-disabled`).
  - 초안을 비우는 때: 이메일 단계가 코드를 새로 보내면 새로 씀, 코드를 다시 받으면 토큰을 지움, 재설정을 마치면 보낸 시각 · 토큰을 지움(이메일은 남겨 `/login/email` 이메일 칸을 채움 — 주소에는 넣지 않습니다), 인증 만료면 보낸 시각 · 토큰을 지움, `/login/email` · `/login` 에 들어오면 이메일만 남기고 지움. 공용 기기에서 새 비밀번호 단계까지 간 뒤 이메일 로그인으로 돌아오면 앞으로 가기로 새 비밀번호 화면에 다시 들어갈 수 없습니다(이메일 단계로 돌려보냄).
  - 재설정이 인증 만료로 돌아오면 `/password/reset?reason=verification-expired` 로 가고 이메일 단계가 "인증 시간이 지났어요. 이메일 인증부터 다시 해 주세요." 를 띄웁니다(가입과 같음).
  - 재설정 목(`features/auth/auth-client.ts`, 가입 인증과 같은 입력 · 저장소는 따로) 재현 입력: 코드 받기 `limit@example.com` → limit, 코드 확인 `000000` → 틀림(5번째에 locked) · `999999` → locked · 5분이 지나면 expired, 재설정 `verify-expired@example.com` 로 받은 토큰 → 늘 인증 만료, `reset-fail@example.com` 로 받은 토큰 → 응답을 받지 못함(빨강 상자, 다시 눌러도 계속 실패). 토큰은 한 번만 쓰고 15분이 지나면 인증 만료입니다. 가입 코드 · 인증으로 재설정을 마칠 수 없고 그 반대도 같습니다.
- 로그인 · 가입 화면의 오른쪽 일러스트도 `public/onboarding/neighborhood.svg` 와 같습니다.
- 검색 결과 없음은 Setup-1-empty 문구(`‘검색어’과 맞는 행정동이 없어요`)를 따르고, 조사(과/와)는 받침에 맞춰 고릅니다. 불러오지 못함은 시안이 없어 같은 모양에 문구만 바꿨습니다.
- 데스크톱 오른쪽 일러스트는 Start-T · Start-D · Setup-1/2/3-D 가 모두 같아 `public/onboarding/neighborhood.svg` 하나로 뽑았습니다.

## 홈 보고 진입 · 목 회원 상태 (S03 · Login-sheet · Consent-health-sheet)

- 보고는 건강정보 동의를 한 회원만 합니다. 홈의 보고 버튼(모바일 하단 · 태블릿 · 데스크톱 머리줄)은 모두 같은 함수를 부르고 회원 상태로 나뉩니다 (`features/home/report-gate.ts`).

  | 회원 상태 | 보고 버튼 | 열리는 것 |
  | --- | --- | --- |
  | 비회원 `guest` | `로그인하고 보고하기` | 로그인 안내 시트 `?report=login` |
  | 동의 안 한 회원 `member-no-consent` | `이번 주 건강 보고하기` | 증상 보고 동의 시트 `?report=health-consent` |
  | 동의한 회원 `member` | `이번 주 건강 보고하기` | 보고 흐름 `?report=start` (S05) |

- 두 시트는 보고 흐름의 앞 단계라 같은 쿼리 `?report=` 의 값으로 둡니다. 버튼으로 열 때는 기록을 쌓고(`push`) 닫기 · 휴대폰 뒤로 가기로 닫힙니다. 동의 시트에서 동의하면 값만 `start` 로 바꿔(`replace`) 뒤로 가기가 동의 시트를 건너뛰어 홈으로 갑니다. 다른 쿼리(`region` · `mock` 등)는 열고 닫아도 남습니다.
- 주소로 바로 들어온 값이 회원 상태에 맞지 않으면 기록을 쌓지 않고 맞는 시트로 바꿉니다: 비회원의 `?report=start`(보고 흐름 어느 단계든) · `health-consent` → `login`, 동의 안 한 회원의 보고 흐름 · `login` → `health-consent`, 동의한 회원의 `login` · `health-consent` → `start`. 보고 흐름과 보낸 보고 표시는 동의한 회원에게만 그립니다.
- 비회원 홈(Home-guest)은 동네 현황(상태 카드 · 공식 정보 · 증상별 변화 · 동네 안내)이 회원 홈과 같고, 자료 부족이면 수치 · 상태색을 숨기는 규칙도 같습니다. 모바일은 하단 버튼 위에 `로그인하면 이번 주 보고를 할 수 있어요`, 태블릿 · 데스크톱은 본문 맨 위(태블릿 격자 위 · 데스크톱 오른쪽 패널 맨 위)에 안내 상자 `로그인하면 이번 주 보고를 할 수 있어요. 동네 현황은 지금처럼 볼 수 있어요.` 를 둡니다.
- 둘러보기에서 온 `?region=<코드>` 는 `app/page.tsx` 가 아는 코드일 때 홈에 넘기고, 홈이 탭바 · 데스크톱 메뉴 링크 뒤에 붙입니다(`/?region=` · `/map?region=` · `/me?region=`).
- 로그인 안내 시트: `카카오로 계속하기` 는 `startKakaoLogin` 이 돌려준 주소(`/setup/region?from=kakao`)로 갑니다(보내는 중 꺼짐, 시작하지 못하면 시트 안 빨강 상자). `이메일로 시작하기` 는 이메일 로그인 `/login/email` 로 갑니다 — 홈을 둘러보다 보고하려는 사람은 이미 회원인 경우가 많고, 이메일 로그인 화면에 `이메일로 가입하기` 링크가 있어 처음인 사람도 바로 가입으로 갈 수 있습니다.
- 증상 보고 동의 시트: S02-4 와 같은 고지 표 · 체크 · 버튼(`features/auth/health-consent.tsx`)을 쓰고, 동의 값도 같습니다(`SENSITIVE_HEALTH_INFO` + 문서 버전). 체크해야 `동의하고 보고하기` 가 켜지고, 보내지 못하면 빨강 상자로 알린 뒤 다시 누를 수 있습니다. `나중에 할게요` 는 닫기입니다. 응답 전에 시트가 닫히면 늦은 응답으로 보고 흐름을 열지 않습니다(동의는 서버 · 목 세션에 남습니다).
- **목 회원 상태** (API 연동 전, `features/auth/auth-client.ts` · `use-mock-auth.ts`):
  - 정하는 순서: ① 주소 `?mock-auth=guest|member|member-no-consent`(QA 용 덮어쓰기, 모르는 값은 무시) ② `auth-client` 모듈 메모리의 목 세션 ③ 기본 `guest`.
  - 목 세션이 바뀌는 때: 카카오 가입 성공(S02-3) · 이메일 로그인 성공(S13-5, 이메일 가입 뒤 S02-3 의 로그인 포함) → `member-no-consent`, 건강정보 동의 성공(S02-4 · 동의 시트) → `member`. 화면 코드는 고치지 않습니다. 로그아웃은 아직 없습니다.
  - 새로고침하면 목 세션이 비어 `guest` 로 돌아갑니다(브라우저 저장소에 남기지 않음). 앱 안 이동(가입 · 로그인 · 동의 뒤 홈으로)에서만 이어집니다.
  - 서버는 목 세션을 몰라 첫 그림(하이드레이션)은 `guest` 로 그립니다. 목 세션은 새로고침하면 비므로 지금은 겉으로 드러나지 않지만, 연동 때 세션을 쿠키 등에서 읽으면 회원이 처음 그릴 때 잠깐 비회원 홈이 보일 수 있습니다 — 서버가 세션을 읽어 넘기도록 함께 바꿉니다.
  - 동의 시트에서 동의하면 `?mock-auth=` 덮어쓰기를 주소에서 지웁니다. 남겨 두면 동의한 뒤에도 미동의로 보여 보고 흐름이 다시 동의 시트로 바뀝니다. 지운 뒤에는 목 세션(`member`)을 따릅니다.
  - 목 로그인은 동의 여부를 몰라 이전에 동의했어도 `member-no-consent` 가 됩니다. 연동 때 서버 세션이 동의 상태를 알려 줍니다.
  - **연동 요구사항**: `useMockAuth` 의 `?mock-auth=` 덮어쓰기는 실제 세션 연동 때 지웁니다. 남기면 주소만으로 회원 · 동의 확인을 건너뛰는 인가 우회가 됩니다.
- 후속 후보: 둘러보기에서 고른 `?region=` 을 로그인 안내 시트 → 가입 흐름의 S02-1 초기 선택으로 넘기기 (지금은 S02-1 에서 다시 고릅니다).
- 시안과 다르게 둔 것:
  - 두 시트는 공통 `Modal` 을 그대로 써서 모바일 시트의 줄 간격이 16(시안 14), 태블릿 · 데스크톱 대화상자 제목이 24(시안 22)입니다. 보고 흐름 · 판단 기준 시트와 같은 값입니다.
  - 태블릿 비회원 안내 상자는 격자 첫 줄(두 칸)이라 아래 간격이 28 입니다(시안은 본문 간격 24).
  - 카카오 버튼은 보내는 중 `disabled` 로 끕니다(S13-1 과 같음).

