# 화면 대응표

| ID | 화면 | 모바일 | 태블릿 | 데스크톱 | 제안 라우트 |
| --- | --- | --- | --- | --- | --- |
| S01 | 시작 | Start | Start-T | Start-D | `/start` |
| S13-1 | 로그인 방법 고르기 (auth 캔버스) | Login, Login-kakao-fail, Login-kakao-exists | -T | -D | `/login` |
| S13-2 | 이메일 가입 · 이메일 (auth 캔버스) | Signup-email | -T | -D | `/signup/email` |
| S13-3 | 이메일 가입 · 인증 코드 (auth 캔버스) | Signup-code | -T | -D | `/signup/code` |
| S13-4 | 이메일 가입 · 비밀번호 · 닉네임 (auth 캔버스) | Signup-account | -T | -D | `/signup/account` |
| S13-5 | 이메일 로그인 (auth 캔버스) | Login-email | -T | -D | `/login/email` |
| S02-1 | 동네 선택 | Setup-1 | Setup-1-T | Setup-1-D | `/setup/region` |
| S02-2 | 성인 확인 | Setup-2 | Setup-2-T | Setup-2-D | `/setup/adult` |
| S02-3 | 동의 | Setup-3 | Setup-3-T | Setup-3-D | `/setup/consent` |
| S03 | 홈 (평소 수준·조금·많이·자료 부족) | Home-good, Home-normal, Home, Home-pending | Tablet, Home-nodata-T | Desktop, Home-nodata-D | `/` |
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
- 흐름: S01 `시작하기` → `/login`(S13-1) → 카카오 신규 회원이면 `/setup/region`. 로그인 화면은 단계 표시가 없습니다.
- `/login` 상태는 주소 쿼리로 받습니다: `?error=kakao-fail` · `?error=kakao-exists` · `?reason=expired`. 연동 때 카카오 콜백 · 세션 만료가 같은 쿼리로 돌려보냅니다. `/login/email?reason=reset-done` 은 비밀번호를 바꾼 뒤입니다.
- 이메일 로그인 목(`features/auth/auth-client.ts`)에서 상태를 재현하는 입력: 이메일 `locked@example.com` → 잠김(locked), 비밀번호 `wrong` → 맞지 않음(wrong), 그 밖 → 성공(홈으로).
- 이메일 가입 흐름: `/login` 이메일로 가입하기 → `/signup/email` → `/signup/code` → `/signup/account` → `/setup/region`. 단계 표시는 없습니다. 입력값은 메모리(Provider)에만 두고, 중간 단계에 바로 들어오면 `/signup/email` 로 돌려보냅니다.
- 가입 목(`features/auth/auth-client.ts`)에서 상태를 재현하는 입력:
  - 코드 받기: 이메일 `exists@example.com` → 이미 가입됨(exists), `limit@example.com` → 요청 많음(limit), 형식이 틀리면 invalid(보낼 때 판단)
  - 코드 확인: `000000` → 틀림(남은 시도 3번부터 1씩 줄고 0 이면 locked), `999999` → 잠김(locked), 5분이 지나면 expired, 그 밖 6자리 → 성공
  - 비밀번호 · 닉네임: 8자 미만이거나 영문 · 숫자 중 하나가 없으면 pw-rule, 확인이 다르면 pw-mismatch, 닉네임 2~10자 밖이면 nick-long(다음을 누를 때 판단)
- 로그인 · 가입 화면의 오른쪽 일러스트도 `public/onboarding/neighborhood.svg` 와 같습니다.
- 검색 결과 없음은 Setup-1-empty 문구(`‘검색어’과 맞는 행정동이 없어요`)를 따르고, 조사(과/와)는 받침에 맞춰 고릅니다. 불러오지 못함은 시안이 없어 같은 모양에 문구만 바꿨습니다.
- 데스크톱 오른쪽 일러스트는 Start-T · Start-D · Setup-1/2/3-D 가 모두 같아 `public/onboarding/neighborhood.svg` 하나로 뽑았습니다.
