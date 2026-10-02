# 프론트엔드 코딩 규칙

구조·import·스타일·환경변수·테스트 규칙의 정본이다. 기계로 강제할 수 있는 규칙은 `eslint.config.mjs` 가 막는다.

## 도구 체인

| 항목          | 버전 · 설정                                                                                                |
| ------------- | ---------------------------------------------------------------------------------------------------------- |
| 런타임        | Node 22 (`.nvmrc`), `engines: ^22.13.0 \|\| >=24`                                                          |
| 패키지 매니저 | pnpm 10 (`packageManager` 고정)                                                                            |
| 프레임워크    | Next.js 16 (App Router, Turbopack), React 19                                                               |
| 언어          | TypeScript 5.9 strict + `noUncheckedIndexedAccess` · `exactOptionalPropertyTypes` · `verbatimModuleSyntax` |
| 스타일        | Tailwind CSS v4 (CSS 우선 설정, `tailwind.config` 없음)                                                    |
| 린트 · 포맷   | ESLint flat config (typed) + Prettier (`prettier-plugin-tailwindcss`)                                      |
| 테스트        | Vitest (기본 node 환경)                                                                                    |

## 디렉터리 구조

```text
frontend/
├── app/                 # 라우트 (App Router). 화면 조립만 하고 로직은 src/ 로 보낸다
│   ├── layout.tsx       # 서체 · 메타데이터 · 뷰포트
│   ├── manifest.ts      # 웹 앱 매니페스트. 아이콘 라우트(icon · apple-icon · app-icons/)와 함께 docs/design/SCREENS.md "앱 매니페스트 · 아이콘"
│   └── globals.css      # Tailwind 진입점 + 토큰 → 테마 매핑
├── src/
│   ├── components/      # 도메인을 모르는 공통 UI (버튼, 리스트 행, 바텀시트 …)
│   ├── features/<도메인>/ # 화면별 UI (home, report, onboarding, region, map, notice, official, admin …)
│   ├── lib/             # 로직. api/ 는 API 호출 계층, env.client.ts 는 공개 환경변수
│   ├── styles/          # tokens.css (토큰 정본) 와 토큰 검사 테스트
│   └── types/           # 공용 타입
├── public/              # 정적 파일. 시안에서 뽑은 그림(onboarding/neighborhood.svg)
└── docs/                # 규칙 정본과 시안 원본
```

- **`lib` · `components` · `types` 는 `features` 를 import 하지 않는다** (ESLint 로 막는다). 의존 방향은 `app → features → components · lib · types` 다.
- `src/types/` 는 아직 없다. 처음 쓰는 PR 에서 만든다.
- 화면 하나는 `src/features/<도메인>/` 에 데이터 모델(`types.ts`) · 화면 조각 · 조립(`*-screen.tsx`)을 둔다. `app/**/page.tsx` 는 데이터를 구해 조립 컴포넌트에 넘기기만 한다 (예: `features/home`).
- 여러 화면이 같이 쓰는 데이터는 화면이 아니라 데이터 도메인에 둔다. 행정동(`features/region`)은 홈 · 첫 진입이 같이 쓴다. 도메인끼리 서로 모르게 해야 하면 `app/` 에서 맞춘다 (홈 `?region=` 은 `app/(home)/page.tsx` 가 `findDistrict` 로 이름을 덮어쓴다).

## import

`simple-import-sort` 가 다섯 묶음으로 정렬한다. 손으로 맞추지 말고 `pnpm lint:fix` 를 돌린다.

1. `react`, `react-dom`, `next`, `next/*`
2. `node:*`
3. 외부 패키지
4. `@/*` (= `src/*`)
5. 상대 경로

타입만 쓰면 `import type` 을 쓴다 (`consistent-type-imports`).

## 스타일

- **토큰만 쓴다.** `globals.css` 가 Tailwind 기본 색·그림자·글자 크기·모서리·경계를 지웠기 때문에 `bg-blue-500`, `shadow-md`, `text-sm` 은 만들어지지 않는다.
- 토큰 유틸리티 이름은 아래와 같다. 원본 값은 `src/styles/tokens.css` 에 있다.

| 종류            | 유틸리티 예                                                                                                                                                                      |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 색              | `text-fg` · `text-fg-sub` · `text-fg-muted` · `bg-bg` · `bg-section` · `border-divider` · `bg-brand` · `bg-info-bg` · `text-danger` · `bg-dim`                                   |
| 상태            | `bg-status-normal` · `text-status-normal-text` (slight · high · insufficient 같은 형식)                                                                                          |
| 글자            | `text-status` · `text-status-desktop` · `text-screen-title` · `text-section-title` · `text-body` · `text-body-strong` · `text-sub` · `text-caption` · `text-tab`                 |
| 첫 진입         | `text-setup-title`(24) · `text-start-title`(28) · `text-start-title-tablet`(38) · `text-hero-title`(36) · `text-lead`(18) · `text-body-large`(16) · `rounded-checkbox`(6)        |
| 이미지 위       | `bg-image-cover`(덮개 0.42) · `text-on-image-sub`(흰 0.85) · `border-on-image-line`(흰 0.5) — 흰 글자는 `text-bg`                                                                |
| 모서리          | `rounded-card` · `rounded-button` · `rounded-chip` · `rounded-sheet` · `rounded-dialog` · `rounded-small` · `rounded-bar`(2) · `rounded-progress`(4) · `rounded-skeleton`(6)     |
| 테두리 · 투명도 | `border-hairline`(1) · `border-emphasis`(1.5) · `border-selected`(2) · `opacity-disabled`(0.5) · `stroke-opacity-inactive`(0.28) — `globals.css` 의 이름 있는 유틸리티           |
| 오버레이        | `text-sheet-title`(22) · `text-dialog-title`(24) · `w-dialog-tablet`(520) · `w-dialog-desktop`(480) · `max-w-dialog` · `max-h-modal` · `pb-sheet` · `pb-safe` · `tracking-brand` |
| 간격 · 크기     | `px-page-mobile` · `px-page-tablet` · `px-page-desktop` · `h-band` · `min-h-touch` · `size-touch` · `h-button` · `h-button-sm` · `h-tab-bar` · `h-header-desktop`                |
| 반응형          | `tablet:` (768px~) · `desktop:` (1280px~) — 잠정값, `globals.css` 주석 참고                                                                                                      |

- 일반 간격(`p-2`, `gap-4`)은 Tailwind 4px 스케일을 그대로 쓴다.
- `[13px]`, `[#3D7EF0]` 같은 arbitrary value 와 raw hex 는 ESLint 가 막는다. 시안에 있는 값이면 `docs/design/tokens.json` → `src/styles/tokens.css` → `app/globals.css` 순서로 올린다.
- **토큰을 바꿀 때는 `tokens.json` 과 `tokens.css` 를 함께 고친다.** 어긋나면 `token-sync.test.ts` 가 실패한다.
- 상태는 항상 색과 글자 라벨을 함께 보인다. `자료 부족` 에서는 상태색을 쓰지 않는다.
- 상태 라벨·색 클래스는 `src/lib/status.ts` 에서만 가져온다. 화면에서 `'평소 수준'` 이나 `text-status-*` 를 다시 적지 않는다.
- 줄 높이 기본값은 `normal` 이다(시안과 맞춤, `globals.css` 주석). 시안이 줄 높이를 적은 곳만 `leading-*` 를 준다.

## 공통 컴포넌트

`src/components/` 에 있다. 도메인을 모르고, 시안의 같은 모양이 두 화면 이상에서 나오면 여기로 올린다.

| 컴포넌트                   | 쓰는 곳 (시안)                    | 고르는 값                                                                                                                                                                                                                                                                                                                     |
| -------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Button`                   | 하단 고정 버튼 · 헤더 · 보고 완료 | `variant`: primary · secondary · danger(빨강 — 동의 철회 · 탈퇴) · text · subtle(회색 글자) / `size`: lg(56) · sm(44) / `fullWidth`                                                                                                                                                                                           |
| `Badge`                    | 공식 정보 행 · 동네 안내 · 지도   | `kind`: official(공식) · citizen(시민 자가보고) · review(운영자 검토)                                                                                                                                                                                                                                                         |
| `StatusWord`               | 홈 상태 카드                      | `status`                                                                                                                                                                                                                                                                                                                      |
| `StatusGauge`              | 홈 상태 카드                      | `status` — 자료 부족이면 회색 · 점 없음                                                                                                                                                                                                                                                                                       |
| `ProgressBar`              | 홈 자료 부족 상태의 참여 인원     | `value` · `max` · `label` — 참여 인원에만 쓰고 증상 비율에는 쓰지 않는다                                                                                                                                                                                                                                                      |
| `ListRow`                  | 증상별 변화 · 공식 정보 행        | `kind`: data · link · menu(내 정보) / `leading` · `trailing` / `divider`                                                                                                                                                                                                                                                      |
| `Section` · `SectionBand`  | 홈 섹션과 섹션 사이 8px 띠        | `title` / `layout`: page(화면 폭) · panel(모바일은 화면 여백, 태블릿부터 격자 · 패널 안이라 여백 없음)                                                                                                                                                                                                                        |
| `Modal`                    | 보고 · 판단 기준                  | `open` · `onClose` · `title` / `step` · `onBack` / `hideHeader` — 모바일 바텀시트, 태블릿 520 · 데스크톱 480 대화상자                                                                                                                                                                                                         |
| `ToastRegion` · `useToast` | 보고 완료 되돌리기                | `toast` · `onAction` / `show` · `dismiss` (기본 5초)                                                                                                                                                                                                                                                                          |
| `TabBar`                   | 모바일 · 태블릿 하단              | `current` / `navSearch`(링크 뒤 쿼리 — 둘러보기 동네) — 데스크톱에서 숨긴다                                                                                                                                                                                                                                                   |
| `AppHeader`                | 모든 사용자 화면 위               | `regionName` · `current` · 동네 · 보고 콜백 / `onNotificationClick`(비회원이면 넘기지 않음 — 종을 그리지 않음) · `reportLabel`(비회원 "로그인하고 보고하기") · `navSearch`(서비스명 · 메뉴 링크) / `title`(내 정보 — 모바일 · 태블릿은 동네 대신 제목). 데스크톱 `내 정보` 는 오른쪽 끝(SCREENS.md "머리줄 · 탭바 공통 규칙") |
| `IconButton`               | 알림 설정 · 닫기 · 이전 단계      | `label`(필수) · `icon`                                                                                                                                                                                                                                                                                                        |
| `ChoiceButton`             | 보고 선택지                       | `label` · `hint`(설명으로 읽힘) / `selected`(여러 개 고르기 — `aria-pressed`) / `size`: lg(64) · md(56)                                                                                                                                                                                                                       |
| `Callout`                  | 보고 수정 안내 · 홈 상단 알림     | `tone`: info · neutral / `icon`                                                                                                                                                                                                                                                                                               |
| `TextField`                | 로그인 · 가입 입력칸              | `label`(위 14) · `hint` · `error`(문자열만, 아래 13, `aria-invalid` · `aria-describedby`) / `counter`(`n/10`, 넘으면 빨강) · `messageAction`(아래 줄 오른쪽) · `trailing`(칸 안 오른쪽, 남은 시간) / `type="password"` 면 보기 버튼 — 기본 1px 회색 · 포커스 2px 네이비 · 오류 2px danger · 꺼짐 회색 바탕                    |
| `AlertBox`                 | 로그인 실패 · 안내 · 잠김         | `tone`: danger(`role=alert`) · info · neutral(`role=status`) / `action` — 여러 줄 문장 + 아래 버튼. 한 줄 안내는 `Callout`                                                                                                                                                                                                    |
| `KakaoButton`              | 카카오로 계속하기                 | 높이 56 — 색은 카카오 가이드 값을 이 컴포넌트에만 둔다(토큰 밖 예외, 근거 주석)                                                                                                                                                                                                                                               |
| `Checkbox`                 | 성인 확인 · 동의                  | `checked` · `onChange` · `label` / `size`: lg(17 굵게) · md(15) — 네이티브 체크 상자를 숨기고 모양만 그린다                                                                                                                                                                                                                   |
| `LoadingState`             | 불러오는 중 (홈 · 내 정보 경계)   | `nav`(지금 메뉴 — 탭바 · 머리줄, 하단 버튼 자리는 홈만)                                                                                                                                                                                                                                                                       |
| `Skeleton`                 | 불러오는 중 막대                  | `shape`: bar(6) · button(12) — 크기는 `className`                                                                                                                                                                                                                                                                             |
| `ErrorState`               | 오류 (`app/error.tsx`)            | `onRetry`(라우트는 Next `retry`) · `nav` — 자료를 대신 보이지 않는다                                                                                                                                                                                                                                                          |
| `OfflineNotice`            | 홈 알림 줄의 오프라인 띠          | `offline`(`useOnline()`) · `receivedAt`(모르면 시각 없이) — 바깥 `aria-live` 는 늘 그려 둔다                                                                                                                                                                                                                                  |
| 아이콘 (`icons.tsx`)       | 시안의 선 아이콘                  | `size` — 색은 글자색(`currentColor`)을 따른다                                                                                                                                                                                                                                                                                 |

- `className` 은 바깥 배치(여백 · 정렬 · 폭)에만 쓴다. 색 · 크기 · 모서리는 컴포넌트가 고르는 값으로 바꾼다.
- 개발 서버의 `/dev/components` 에서 전부 볼 수 있다. 시안과 나란히 놓고 비교하는 용도이고 프로덕션에서는 404 다.
- `Button` 은 기본이 `shrink-0` 이다. 한 줄에 버튼 둘을 나란히 둘 때는 `fullWidth` 만으로는 각자 100% 가 되어 넘친다 — `flex-1` 로 폭을 나눈다 (보고 완료 대화상자).
- **기본 클래스와 같은 속성을 `className` 으로 덮어쓰지 않는다.** 예: `Button` 의 `inline-flex` 에 `hidden` 을 더하면 어느 쪽이 이길지는 클래스를 적은 순서가 아니라 Tailwind 가 CSS 를 만든 순서로 정해진다. 숨기거나 바꿔야 하면 감싸는 요소에 준다(`AppHeader` 의 보고 버튼 참고).
- `Modal` 은 네이티브 `<dialog>` 의 `showModal()` 을 쓴다. 포커스 가두기 · 뒤 화면 비활성 · Esc 를 브라우저가 맡는다. 상태(`open`)는 부모가 갖고, Esc · 바깥 누르기 · 닫기 버튼은 모두 `onClose` 로 모인다.
- 토스트 영역(`role="status"`)은 알림이 없어도 늘 그려 둔다. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽는다.
- 화면 맨 아래 붙는 요소는 홈 인디케이터 영역을 비운다(`pb-safe`, 시트는 `pb-sheet`). 레이아웃이 `viewport-fit=cover` 라 iOS PWA 에서 값이 생긴다.

## 데이터와 환경변수

- **`자료 부족` 의 수치는 타입에서 막는다.** 화면 데이터 모델은 상태로 갈리는 유니온으로 두고, `insufficient` 쪽에는 증상 비율 · 기준선 · 증상별 변화 필드를 아예 두지 않는다 (`features/home/types.ts`). 화면 코드가 실수로 수치를 그리면 타입 오류가 난다.
- **페이지 사이에 넘기는 값은 라우트 레이아웃의 Provider(React context)에 둔다** (`app/(onboarding)/layout.tsx` 의 고른 동네 · 성인 확인). 괄호 폴더(route group)는 주소를 바꾸지 않고 여러 화면을 한 레이아웃으로 묶는다. 레이아웃은 그 화면들 사이를 오가도 다시 그려지지 않는다. 새로고침하면 사라지므로 다음 단계는 값이 없으면 앞 단계로 `replace` 한다.
- **화면의 "뒤로" 는 기록을 쌓지 않는다.** 앱 안에서 거쳐 왔으면 `router.back()`, 주소로 바로 들어와 앞 기록이 없으면 `router.replace(돌아갈 곳)` 다 — 뒤로가 사이트 밖으로 나가지 않는다. 판별은 루트 레이아웃(`app/layout.tsx`)의 `NavTrailProvider` 가 앱 안 이동 경로를 기록해서 하고, 화면은 `src/lib/use-nav-trail.tsx` 의 `useNavTrail().goBack(돌아갈 곳, 앞 화면 후보?)` 하나로 부른다. 앞 단계가 정해진 화면(첫 진입 단계 · 내 정보 아래 계정 화면)은 후보를 주고 바로 앞이 그 후보일 때만 되돌린다. 여러 화면에서 들어오는 화면(공식 정보 · 동네 안내 · 설치 안내)은 후보를 생략해 앱 안 어디서 왔든 되돌린다. 진입 링크는 따로 표시를 남기지 않는다(그냥 `Link`). **앱 안 replace 는 모두 `useNavTrail().replace` 로 한다**(가드 · 로그인 만료 감시 · 로그인 성공 · 로그아웃 포함, ESLint `no-restricted-syntax` 가 `router.replace` 를 막는다 — 예외는 기록 자신뿐). `router.replace` 를 바로 부르면 기록이 쌓은 것으로 세어 실제 브라우저 기록보다 길어지고, 휴대폰 뒤로로 바로 연 첫 화면에 돌아왔을 때 뒤로가 사이트 밖으로 나간다. 그래서 replace 를 거는 컴포넌트는 Provider 안에 둔다(로그인 만료 감시도 루트 레이아웃의 Provider 안이다). replace 는 바꿔 갈 경로를 걸어 두고 그 경로에 닿을 때만 맨 끝을 바꾼다 — 화면이 도착한 커밋의 effect 에서 걸어도(가드 · 값이 없어 앞 단계로 돌려보냄) 도착 이동에 잘못 쓰이지 않고, 같은 경로로의 replace(쿼리만 바뀜)와 Next 가 버린 이동은 걸어 두지 않거나 버린다(`src/lib/nav-trail.ts` `settleReplace`). 기록은 경로만 보고(쿼리 무시) 새로고침 · 새 탭이면 비어 시작한다. 한계: 브라우저 뒤로 · 앞으로를 링크 이동과 구분하지 않아, 바로 앞앞 주소로 링크를 따라가면 뒤로 간 것으로 보고 앞으로 가기는 새로 쌓는다.
  - 루트에 두는 비용: 주소가 바뀔 때마다 Provider 하나가 다시 그려지고 effect 하나가 배열을 고친다. 값(`goBack` · `replace`)이 바뀌지 않아 아래 화면은 다시 그리지 않는다. Provider 밖(화면 단독 테스트)이면 앞 기록을 모르는 것으로 보고 늘 replace 한다.
  - #109 전에는 화면마다 판단이 달랐다. 한 기록으로 묶은 이유:

    | 화면                  | 이전 방식                                     | 한계                                                                                                                                           |
    | --------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
    | 첫 진입 · 내 정보     | 레이아웃 Provider 마다 이동 경로 기록         | 레이아웃을 나갔다 브라우저 뒤로로 돌아오면 기록이 비어 앞 화면이 기록에 두 번 남음                                                             |
    | 공식 정보             | 문서를 처음 연 주소(Navigation Timing)        | 바로 열고 홈을 거쳐 다시 오거나 새로고침 · bfcache 없이 돌아오면 홈이 두 번 남음. 다른 문서에서 replace 로 들어오면 사이트 밖으로 나갈 수 있음 |
    | 동네 안내 · 설치 안내 | 진입 링크가 남긴 표시를 화면이 마운트 때 소비 | 진입 링크마다 표시를 남겨야 함. 휴대폰 뒤로 · 앞으로로 다시 그리면 앞 기록이 있어도 홈이 두 번 남음                                            |
- **로딩 경계(`loading.tsx`)는 루트에 두지 않고 주요 메뉴 화면에만 둔다** (`app/(home)/loading.tsx` · `app/me/loading.tsx`). 루트에 두면 모든 경로가 Suspense 안에서 스트리밍되어 `notFound()` 가 404 대신 200 으로 나간다. 홈을 괄호 폴더 `(home)` 에 둔 것도 경계를 홈에만 걸기 위해서다(주소는 `/`).
- **브라우저 연결 상태는 `src/lib/use-online.ts` 의 `useOnline()` 으로만 읽는다.** 서버 그림과 하이드레이션 첫 그림은 늘 온라인이다(SSR 불일치 방지). 오프라인 안내는 화면 전체를 바꾸지 않고 띠(`OfflineNotice`)로 보인다.
- **로그인 만료는 `src/lib/session-expiry.ts` 의 `notifySessionExpired()` 하나로 알린다.** 루트 레이아웃의 `features/auth/session-expiry-watcher.tsx` 가 세션을 비우고 `/login?reason=expired` 로 `replace` 한다. 연동 때 API 계층의 401 처리가 부르고, 화면 코드는 401 을 따로 다루지 않는다.
- **회원만 보는 화면의 가드는 하이드레이션을 마친 뒤 판단한다.** 서버와 하이드레이션 첫 그림의 회원 상태는 늘 `guest` 라(`useMockAuth`) 그 값으로 로그인에 보내면 회원도 튕긴다. `src/lib/use-hydrated.ts` 가 true 인 그림의 상태로만 판단하고 Next 라우터(`router.replace`)로 보낸다 (`features/me/member-gate.ts`).
- **router 내비게이션이 대기 중일 때 `history.replaceState` · `pushState` 를 부르면 Next 가 그 내비게이션을 버린다 — 가드가 보낼 곳이 있으면 주소 정리를 하지 않는다.** 원시 history 변경이 Next 의 복원(ACTION_RESTORE)을 일으켜 대기 중인 `router.replace` 가 버려진다(프로덕션 빌드에서 재현). 같은 그림에서 주소 쿼리를 정리하는 화면(홈의 `?report=` · 내 정보의 `?confirm=` 정리)은 `useRequiredStepsGate` · `useRequiredStepsTarget`(`features/me/member-gate.ts`)이 돌려준 보낼 곳이 있으면 정리를 건너뛴다.
- **레이아웃 · 정적 라우트에서 `useSearchParams` 를 읽는 클라이언트 컴포넌트는 `<Suspense>` 로 감싼다.** 감싸지 않으면 `next build` 의 정적 생성이 `missing-suspense-with-csr-bailout` 으로 멈춘다(dev 서버에서는 드러나지 않는다). 하이드레이션 뒤에야 그리는 화면은 대체 그림을 비워 둔다(`app/me/layout.tsx` 의 가드, `app/(onboarding)/terms/reconsent/page.tsx`). 페이지가 `searchParams` 를 await 하면 동적 라우트라 필요 없다.
- **돌아갈 곳(`?next=`)은 허용 목록 안의 경로만 받는다.** 가드가 다른 화면으로 보냈다가 돌려보낼 때 `?next=` 를 쓰고, 받는 쪽은 `features/auth/required-steps.ts` 의 `safeNextPath` 로 정확히 같은 경로(`NEXT_PATHS`: `/` · `/me` · `/me/devices` · `/me/password`)일 때만 따른다. 로그인 화면(`/login` → `/login/email`)도 `features/auth/login-return.ts` 로 `?next=` 를 받아 로그인 뒤 그곳으로 간다(내 정보 가드가 씀). 로그인으로 넘기는 쿼리는 `next` 와 둘러보기 동네뿐이고 목 덮어쓰기는 넘기지 않는다(로그인이 세션을 바꾸므로). 쿼리 · `#` 가 붙었거나 다른 오리진(`//…` · `https://…`)이면 홈으로 보낸다(오픈 리다이렉트 방지). 함께 넘길 쿼리는 둘러보기 동네(`region`)와 QA 용 목 덮어쓰기(`mock-auth` · `mock-provider` · `mock-required`)뿐이다(`carriedParams`). 새 화면을 가드에 걸면 목록에 더한다.
- **화면 위에 뜨는 시트 · 대화상자의 열림 상태는 주소 쿼리에 둔다** (`docs/design/SCREENS.md` 의 제안 라우트, 예: 판단 기준 `/?explain=1`). 새로고침 · 공유해도 같은 화면이 열린다. `src/lib/use-modal-param.ts` 를 쓴다. 단계마다 `push` 로 기록을 쌓아 휴대폰 뒤로 가기가 이전 단계 · 닫기로 이어지게 하고, 보낸 뒤 완료처럼 되돌아오면 안 되는 단계는 `replace` 로 바꾼다(`remove` 로 다른 쿼리를 같은 기록 항목에서 함께 지울 수 있다). `close` 는 이 훅이 쌓은 깊이만큼만 되돌린다 — 깊이는 `history.state` 에 두어 뒤로 가기로 단계를 되돌린 뒤 닫아도 홈 앞까지만 간다. 주소로 바로 들어와 쌓은 기록이 없으면 쿼리만 지운다. `router.push` 는 서버에 화면을 다시 요청하므로 쓰지 않는다.
- **같은 문서 안 `#` 링크(`<a href="#id">`)를 쓰지 않는다.** Next 가 모르는 기록 항목(`history.state` 가 null)이 생겨, 그 뒤 연 시트 · 대화상자의 닫기(`history.go(-1)`)가 그 항목으로 돌아가고 Next 가 무시해 첫 닫기에 닫히지 않는다. 섹션 바로가기는 버튼으로 `scrollIntoView` 한 뒤 제목(`tabIndex={-1}`)에 포커스를 준다(`features/me/me-screen.tsx`).
- **마운트 effect 에서 history 를 바꾸지 않는다.** 하이드레이션 첫 커밋에서는 Next 가 아직 history 를 감싸지 않는다(최상위 라우터 effect 보다 자식 effect 가 먼저 돈다) — 그때 바꾼 주소는 `useSearchParams` 가 모른다. 미뤄야 하면 `setTimeout(0)` 으로 미루고 cleanup 에서 취소한다(`features/home/home-screen.tsx` 의 보고 진입 정리). 열림처럼 그 값으로 그리는 것은 바뀔 값으로 미리 계산해 첫 그림부터 맞춘다.
- 서버에 보내는 동작(보고 보내기 · 고치기 · 되돌리기)은 연동 전에도 `features/<도메인>/*-client.ts` 에 Promise 를 돌려주는 함수로 둔다. 연동 때 함수 안만 `src/lib/api/` 호출로 바꾸고 화면 코드는 그대로 둔다 (`features/report/report-client.ts`).
- API 연동 전 화면은 `features/<도메인>/mock.ts` 의 목 데이터로 만든다. 목 데이터의 기본값은 `자료 부족` 처럼 수치를 지어내지 않는 상태로 둔다. 연동 이슈에서 목 데이터를 지운다.

- 화면 코드(`app/`, `src/features/`, `src/components/`)에서 `fetch` 를 직접 부르지 않는다. API 호출은 `src/lib/api/` 로 모은다 (ESLint 로 막는다).
- 공개 환경변수는 `src/lib/env.client.ts` 에서만 읽는다. `process.env.NEXT_PUBLIC_X` 는 리터럴로 읽어야 빌드 때 치환된다.
- 목록은 `.env.example` 에 있다. 로컬 개발도 dev 게이트웨이(`https://api-dev.sneezecast.com`)를 쓴다.
- `localStorage` · `sessionStorage` 는 ESLint 가 막는다. 건강·증상 정보는 민감정보라 브라우저에 아무렇게나 남기지 않는다. 기기 토큰처럼 꼭 필요한 값은 저장 모듈 하나로 모으고 그 줄에 근거 주석을 남긴다.

## 테스트

- 파일은 대상 옆에 `*.test.ts(x)` 로 둔다.
- 기본 환경은 node 다. DOM 이 필요한 컴포넌트 테스트는 파일 맨 위에 `// @vitest-environment jsdom` 을 적어 그 파일만 켠다.
- 컴포넌트 테스트는 Testing Library(`@testing-library/react` · `user-event`)로 역할·이름으로 찾는다(`getByRole`). 렌더 정리는 `src/test/setup.ts` 가 DOM 환경일 때만 등록한다.
- 테스트에는 Next 라우터가 없다. `useSearchParams` 를 쓰는 화면은 `vi.mock('next/navigation', …)` 으로 쿼리를 흉내 낸다 (`features/home/home-screen.test.tsx`).
- 스타일은 클래스 이름(`classList`)으로 확인한다. 실제 픽셀 값은 `/dev/components` 를 브라우저로 열어 확인한다.
- jsdom 은 `<dialog>` 의 `showModal()` · `close()` 가 없어 `src/test/setup.ts` 가 `open` 속성만 흉내 낸다. 포커스 가두기 · Esc 같은 실제 동작은 브라우저로 확인한다.
- jsdom 은 27 을 쓴다. 30 은 Node 22.22.2 이상을 요구해 `engines`(22.13 이상)와 맞지 않는다.

## 완료 전 확인

```bash
pnpm qa:verify   # format:check → lint → typecheck → test → build
```

통과하지 않으면 완료로 보고하지 않는다. 실패나 미실행은 PR 의 "검증 내역" 에 그대로 적는다.
