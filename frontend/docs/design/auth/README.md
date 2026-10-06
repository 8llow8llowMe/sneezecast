# 로그인 · 가입 · 계정 화면 (별도 캔버스)

Claude Design 캔버스 "우리동네체온계 로그인·가입·계정 시안"에서 내보낸 원본입니다.
디자인 시스템은 기존 `docs/design/tokens.json`과 같습니다. 읽는 법은 `docs/design/README.md`를 따릅니다.

## 이 폴더만의 규칙

- 상태가 여러 개인 화면은 파일 하나에 상태별 마크업이 모두 들어 있습니다. `<sc-if value="{{is_<상태>}}">`로 나뉘고, 아래 스크립트의 `state` 기본값이 캔버스에 보이는 상태입니다.
- 새 색은 카카오 버튼 하나뿐입니다: 배경 `#FEE500`, 심볼 검정, 글자 `rgba(0, 0, 0, 0.85)`.
- 오류 색은 기존 토큰 `danger #C0392B`를 씁니다. 오류 알림 바탕은 `#FDEDEB`입니다(토큰에 `dangerBg`로 추가해 주세요).
- 입력칸: 높이 52 · 모서리 12 · 기본 1px `#D1D6DB` · 포커스 2px `#1F2F57` · 오류 2px `#C0392B`. 라벨은 위(14 굵게), 도움말·오류는 아래 한 줄(13).
- `Home-member`는 시트 배경용입니다. 기존 홈과 같으므로 새로 구현하지 않습니다.
- 비밀번호 재설정의 이메일·코드 단계는 `Signup-email`, `Signup-code`를 제목과 버튼 문구만 바꿔 씁니다.
- 수치, 이메일, 기기 이름은 모두 예시입니다. 개별 보고 보관 기간은 52주로 확정입니다.

## 화면 대응표

| ID | 화면 | 파일 (모바일 / -T / -D) | 상태 (state) | 우선순위 | 제안 라우트 |
| --- | --- | --- | --- | --- | --- |
| S13-1 | 로그인 방법 선택 | Login | default · kakao-fail · kakao-exists(→ #167 에서 `/login/kakao/link`(계정 연결 확인)로 옮김) · expired | 필수 | `/login` |
| S13-2 | 이메일 입력 | Signup-email | default · invalid · exists · limit | 필수 | `/signup/email` |
| S13-3 | 인증 코드 | Signup-code | default · wrong · expired · locked | 필수 | `/signup/code` |
| S13-4 | 비밀번호 · 닉네임 | Signup-account | default · pw-rule · pw-mismatch · nick-long | 필수 | `/signup/account` |
| S13-5 | 이메일 로그인 | Login-email | default · wrong · locked · reset-done | 필수 | `/login/email` |
| S13-6 | 새 비밀번호 | Password-reset | default · mismatch · rule | 다음 | `/password/reset` |
| S02-1 | 동네 선택 (1 / 4) | Setup-1, Setup-1-empty | — | 필수 | `/setup/region` |
| S02-1 | 둘러보기용 동네 선택 | Setup-1-browse | — | 필수 | `/browse/region` |
| S02-1 | 폐지된 동네 다시 고르기 | Setup-1-reselect | — | 다음 | `/setup/region?reselect=1` |
| S02-2 | 성인 확인 (2 / 4) | Setup-2 | — | 필수 | `/setup/adult` |
| S02-3 | 가입 동의 (3 / 4) | Setup-3 | default · incomplete | 필수 | `/setup/terms` |
| S02-3 | 약관 재동의 | Setup-3-reconsent | — | 다음 | `/terms/reconsent` |
| S02-4 | 증상 보고 동의 (4 / 4) | Setup-4 | unchecked · checked | 필수 | `/setup/health-consent` |
| S02-4 | 증상 보고 동의 시트 | Consent-health-sheet | unchecked · checked | 필수 | 홈 위 모달 |
| S03 | 둘러보기 홈 | Home-guest | level 자료 부족 | 필수 | `/` (비로그인) |
| S03 | 로그인 안내 시트 | Login-sheet | — | 필수 | 홈 위 모달 |
| S03 | 동의 철회 직후 홈 | Home-purging | — | 다음 | `/` |
| S10 | 내 정보 (이메일 / 카카오 / 비로그인) | Settings, Settings-kakao, Settings-guest | — | 다음 | `/me` |
| S10 | 로그인한 기기 | Settings-devices | — | 다음 | `/me/devices` |
| S10 | 비밀번호 변경 | Settings-password | — | 다음 | `/me/password` |
| S10 | 내 동네 바꾸기 (#141) | 시안 없음 — Settings-password 구조를 따름 | — | — | `/me/region` |
| S10 | 서비스 안내 (#193, 비회원도 봄) | 시안 없음 — Settings 의 개인정보 · 서비스 정보 섹션 행만 있음. Settings-password 틀을 따름 | — | — | `/me/privacy` · `/me/data-sources` · `/me/ai` |
| S10 | 확인 대화상자 | Confirm-consent-withdraw, Confirm-withdraw, Confirm-logout | — | 다음 | 모달 |
| 공통 | 로그인 만료 토스트 | State-session-expired | — | 다음 | → `/login` |

## 기존 화면에서 바뀌는 점

- S02 단계 표시가 `1 / 4` ~ `4 / 4`로 바뀝니다.
- S02-3에서 건강정보 동의와 "이렇게 다뤄요" 표를 빼고 S02-4로 옮깁니다.
- 내 정보의 "내 보고 전체 삭제"는 "건강정보 동의 철회"로 바뀝니다.
- 기존 시안의 "12주" 표기는 모두 52주로 고칩니다.
