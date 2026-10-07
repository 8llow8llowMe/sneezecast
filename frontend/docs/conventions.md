# 프론트엔드 코딩 규칙

구조·import·스타일·환경변수·테스트 규칙의 정본이다. 기계로 강제할 수 있는 규칙은 `eslint.config.mjs` 가 막는다.

## 도구 체인

| 항목          | 버전 · 설정                                                                                                              |
| ------------- | ------------------------------------------------------------------------------------------------------------------------ |
| 런타임        | Node 22 (`.nvmrc`), `engines: ^22.13.0 \|\| >=24`                                                                        |
| 패키지 매니저 | pnpm 10 (`packageManager` 고정)                                                                                          |
| 프레임워크    | Next.js 16 (App Router, Turbopack), React 19                                                                             |
| 언어          | TypeScript 5.9 strict + `noUncheckedIndexedAccess` · `exactOptionalPropertyTypes` · `verbatimModuleSyntax`               |
| 스타일        | Tailwind CSS v4 (CSS 우선 설정, `tailwind.config` 없음)                                                                  |
| 린트 · 포맷   | ESLint flat config (typed) + Prettier (`prettier-plugin-tailwindcss`)                                                    |
| 테스트        | Vitest (기본 node 환경)                                                                                                  |
| 성능 측정     | Lighthouse CI (`@lhci/cli`, Lighthouse 12) — 프로덕션 빌드 · 모바일 기본 설정, 경고만 ([performance.md](performance.md)) |

## 디렉터리 구조

```text
frontend/
├── app/                 # 라우트 (App Router). 화면 조립만 하고 로직은 src/ 로 보낸다
│   ├── layout.tsx       # 서체 · 메타데이터 · 뷰포트. `connection()` 으로 모든 화면을 동적 렌더링한다(아래 "보안 헤더 · CSP")
│   ├── manifest.ts      # 웹 앱 매니페스트. 아이콘 라우트(icon · apple-icon · app-icons/)와 함께 docs/design/SCREENS.md "앱 매니페스트 · 아이콘"
│   └── globals.css      # Tailwind 진입점 + 토큰 → 테마 매핑
├── next.config.ts       # 빌드 설정 · 모든 응답의 보안 헤더(headers())
├── proxy.ts             # Next 16 Proxy(옛 미들웨어). 화면 요청에 CSP(요청마다 nonce)를 싣고, 카카오 콜백 쿼리를 fragment 로 303 리다이렉트하고, 처음 온 사람을 시작 화면으로 보낸다 — 로직은 lib/security/ · features/auth/kakao-callback-redirect.ts · features/onboarding/first-visit.ts
├── src/
│   ├── components/      # 도메인을 모르는 공통 UI (버튼, 리스트 행, 바텀시트 …)
│   ├── features/<도메인>/ # 화면별 UI (home, report, onboarding, region, map, notice, official, admin …)
│   ├── lib/             # 로직. api/ 는 API 호출 계층, session/ 은 세션 저장소, security/ 는 보안 헤더 · CSP, env.client.ts 는 공개 환경변수
│   ├── styles/          # tokens.css (토큰 정본) 와 토큰 검사 테스트
│   └── types/           # 공용 타입
├── public/              # 정적 파일. 시안에서 뽑은 그림(onboarding/neighborhood.svg)
├── scripts/             # 도구 스크립트(.mjs). Lighthouse 측정 전 확인(lighthouse-preflight) · 결과 요약(lighthouse-summary) — docs/performance.md
├── lighthouserc.yml     # Lighthouse CI 설정(대상 URL · 예산)
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

**`'use client'` 가 없는 모듈은 `'use client'` 모듈에서 컴포넌트가 아닌 값(상수 · 함수)을 가져오지 않는다.** 서버 페이지에서 그 값은 실제 값이 아니라 클라이언트 참조가 된다 — 쿼리 이름 상수를 가져오면 `searchParams.get()` 이 늘 null 이다(#206, 둘러볼 동네 고르기에서 돌아오면 `mock-auth` · `mock-provider` 가 빠졌다). 서버 · 클라이언트가 같이 쓰는 값은 `'use client'` 가 없는 모듈에 두고 훅 모듈은 다시 내보내기만 한다(예: `features/auth/mock-params.ts`). vitest 에는 이 경계가 없어 `src/client-boundary.test.ts` 가 import 를 훑어 막는다.

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
| `TabBar`                   | 모바일 · 태블릿 하단              | `current`(없는 화면 404 는 null — 표시 없음) / `navSearch`(링크 뒤 쿼리 — 둘러보기 동네) — 데스크톱에서 숨긴다                                                                                                                                                                                                                |
| `AppHeader`                | 모든 사용자 화면 위               | `regionName` · `current` · 동네 · 보고 콜백 / `onNotificationClick`(비회원이면 넘기지 않음 — 종을 그리지 않음) · `reportLabel`(`reportButtonLabel` 의 3갈래) · `navSearch`(서비스명 · 메뉴 링크) / `title`(내 정보 — 모바일 · 태블릿은 동네 대신 제목). 데스크톱 `내 정보` 는 오른쪽 끝(SCREENS.md "머리줄 · 탭바 공통 규칙") |
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
- **화면 첫 그림에 보이지 않는 시트 · 대화상자는 처음 열 때 받는다**(지연 로드, #184). 모듈 맨 위에서 `lazyComponent(() => import(…))` 로 만들고 화면에서 `useLazyComponent(그것, 열림, 실패 시 알림 · 닫기)` 로 받아 null 이 아닐 때 그린다(`src/lib/lazy-component.ts`, 홈의 보고 흐름 · 로그인 안내 · 동의 · 판단 기준). 한 번 받으면 닫혀도 그린다. 화면은 하이드레이션 뒤 유휴 시간에 미리 받고(`preloadWhenIdle`), 받는 동안에도 화면이 눌리므로 시트를 여는 함수는 누른 순간 주소에 그릴 수 있는 시트의 쿼리가 이미 있으면 열지 않는다(같은 기록이 두 번 쌓이거나 시트 둘이 겹치지 않게). `next/dynamic` 은 시트에 쓰지 않는다 — Suspense 를 거쳐 여는 데 약 300ms 가 더 걸린다. 다른 화면이 쓰는 상수(쿼리 이름 등)는 시트 모듈이 아니라 가벼운 모듈(`types.ts`)에 두어 시트 코드를 끌어오지 않게 한다.
- 토스트 영역(`role="status"`)은 알림이 없어도 늘 그려 둔다. 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽는다.
- 화면 맨 아래 붙는 요소는 홈 인디케이터 영역을 비운다(`pb-safe`, 시트는 `pb-sheet`). 레이아웃이 `viewport-fit=cover` 라 iOS PWA 에서 값이 생긴다.

## 데이터와 환경변수

- **`자료 부족` 의 수치는 타입에서 막는다.** 화면 데이터 모델은 상태로 갈리는 유니온으로 두고, `insufficient` 쪽에는 증상 비율 · 기준선 · 증상별 변화 필드를 아예 두지 않는다 (`features/home/types.ts`). 화면 코드가 실수로 수치를 그리면 타입 오류가 난다.
- **페이지 사이에 넘기는 값은 라우트 레이아웃의 Provider(React context)에 둔다** (`app/(onboarding)/layout.tsx` 의 고른 동네 · 성인 확인). 괄호 폴더(route group)는 주소를 바꾸지 않고 여러 화면을 한 레이아웃으로 묶는다. 레이아웃은 그 화면들 사이를 오가도 다시 그려지지 않는다. 새로고침하면 사라지므로 다음 단계는 값이 없으면 앞 단계로 `replace` 한다.
- **화면의 "뒤로" 는 기록을 쌓지 않는다.** 앱 안에서 거쳐 왔으면 `router.back()`, 주소로 바로 들어와 앞 기록이 없으면 `router.replace(돌아갈 곳)` 다 — 뒤로가 사이트 밖으로 나가지 않는다. 판별은 루트 레이아웃(`app/layout.tsx`)의 `NavTrailProvider` 가 앱 안 이동 경로를 기록해서 하고, 화면은 `src/lib/use-nav-trail.tsx` 의 `useNavTrail().goBack(돌아갈 곳, 앞 화면 후보?)` 하나로 부른다. 앞 단계가 정해진 화면(첫 진입 단계 · 내 정보 아래 계정 화면)은 후보를 주고 바로 앞이 그 후보일 때만 되돌린다. 여러 화면에서 들어오는 화면(공식 정보 · 동네 안내 · 설치 안내 · 내 정보 아래 서비스 안내 `/me/privacy` · `/me/data-sources` · `/me/ai`)은 후보를 생략해 앱 안 어디서 왔든 되돌린다. 앞 화면은 정해지지 않았지만 몇몇 화면으로는 되돌리지 않을 때는 후보 대신 바로 앞 경로를 받는 함수를 준다(돌아갈 곳이 있는 로그인 방법 고르기 — 흐름 단계 `isFlowStepPath` 로는 되돌리지 않음, #140). 진입 링크는 따로 표시를 남기지 않는다(그냥 `Link`). **앱 안 replace 는 모두 `useNavTrail().replace` 로 한다**(가드 · 로그인 만료 감시 · 로그인 성공 · 로그아웃 포함, ESLint `no-restricted-syntax` 가 `router.replace` 를 막는다 — 예외는 기록 자신뿐). `router.replace` 를 바로 부르면 기록이 쌓은 것으로 세어 실제 브라우저 기록보다 길어지고, 휴대폰 뒤로로 바로 연 첫 화면에 돌아왔을 때 뒤로가 사이트 밖으로 나간다. 그래서 replace 를 거는 컴포넌트는 Provider 안에 둔다(로그인 만료 감시도 루트 레이아웃의 Provider 안이다). replace 는 바꿔 갈 경로를 걸어 두고 그 경로에 닿을 때만 맨 끝을 바꾼다 — 화면이 도착한 커밋의 effect 에서 걸어도(가드 · 값이 없어 앞 단계로 돌려보냄) 도착 이동에 잘못 쓰이지 않고, 같은 경로로의 replace(쿼리만 바뀜)와 Next 가 버린 이동은 걸어 두지 않거나 버린다(`src/lib/nav-trail.ts` `settleReplace`). 기록은 경로만 보고(쿼리 무시) 새로고침 · 새 탭이면 비어 시작한다. 한계: 브라우저 뒤로 · 앞으로를 링크 이동과 구분하지 않아, 바로 앞앞 주소로 링크를 따라가면 뒤로 간 것으로 보고 앞으로 가기는 새로 쌓는다.
  - 루트에 두는 비용: 주소가 바뀔 때마다 Provider 하나가 다시 그려지고 effect 하나가 배열을 고친다. 값(`goBack` · `replace`)이 바뀌지 않아 아래 화면은 다시 그리지 않는다. Provider 밖(화면 단독 테스트)이면 앞 기록을 모르는 것으로 보고 늘 replace 한다.
  - #109 전에는 화면마다 판단이 달랐다. 한 기록으로 묶은 이유:

    | 화면                  | 이전 방식                                     | 한계                                                                                                                                           |
    | --------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
    | 첫 진입 · 내 정보     | 레이아웃 Provider 마다 이동 경로 기록         | 레이아웃을 나갔다 브라우저 뒤로로 돌아오면 기록이 비어 앞 화면이 기록에 두 번 남음                                                             |
    | 공식 정보             | 문서를 처음 연 주소(Navigation Timing)        | 바로 열고 홈을 거쳐 다시 오거나 새로고침 · bfcache 없이 돌아오면 홈이 두 번 남음. 다른 문서에서 replace 로 들어오면 사이트 밖으로 나갈 수 있음 |
    | 동네 안내 · 설치 안내 | 진입 링크가 남긴 표시를 화면이 마운트 때 소비 | 진입 링크마다 표시를 남겨야 함. 휴대폰 뒤로 · 앞으로로 다시 그리면 앞 기록이 있어도 홈이 두 번 남음                                            |
- **로딩 경계(`loading.tsx`)는 루트에 두지 않고 주요 메뉴 화면에만 둔다** (`app/(home)/loading.tsx` · `app/map/loading.tsx` · `app/me/loading.tsx`). 루트에 두면 모든 경로가 Suspense 안에서 스트리밍되어 `notFound()` 가 404 대신 200 으로 나간다. 홈을 괄호 폴더 `(home)` 에 둔 것도 경계를 홈에만 걸기 위해서다(주소는 `/`). 지도 · 내 정보는 경로 폴더(`app/map` · `app/me`)라 그 경로에만 걸려 괄호 폴더가 필요 없다.
- **브라우저 연결 상태는 `src/lib/use-online.ts` 의 `useOnline()` 으로만 읽는다.** 서버 그림과 하이드레이션 첫 그림은 늘 온라인이다(SSR 불일치 방지). 오프라인 안내는 화면 전체를 바꾸지 않고 띠(`OfflineNotice`)로 보인다.
- **로그인 만료는 `src/lib/session-expiry.ts` 의 `notifySessionExpired()` 하나로 알린다.** 루트 레이아웃의 `features/auth/session-expiry-watcher.tsx` 가 (목데이터 모드면 목 세션을 비우고) `/login?reason=expired` 로 `replace` 한다. 지금 화면이 돌아갈 곳 허용 목록(아래 `NEXT_PATHS`) 안이면 `&next=` 로, 둘러보기 동네는 `&region=` 으로 싣는다(`login-return.ts` 의 `loginReturnTo` · `expiredLoginHref`, #140) — 회원 화면 가드(`useMemberGate`)도 만료 중이면 같은 주소로 보내 어느 쪽이 나중이어도 같은 곳에 닿는다. 실데이터 모드는 세션 저장소가 재발급에 실패했을 때 세션을 먼저 비우고 부른다(아래 "세션 저장소"). 화면 코드는 401 을 따로 다루지 않는다.
- **회원만 보는 화면의 가드는 회원 상태가 정해진 뒤 판단한다.** 서버와 하이드레이션 첫 그림의 회원 상태는 늘 `guest` 이고(`useAuth`), 실데이터 모드는 새로고침 뒤 세션을 되살리는(재발급) 동안에도 `guest` 다. 그 값으로 로그인에 보내면 회원도 튕긴다. `features/auth/use-auth.ts` 의 `useAuthSettled()`(하이드레이션을 마쳤고, 목데이터이거나 실데이터 세션이 `member` · `guest` 로 정해짐)가 true 인 그림의 상태로만 판단하고 Next 라우터(`router.replace`)로 보낸다 (`features/me/member-gate.ts`, 약관 재동의 · 동네 다시 고르기 화면도 같다).
  - 반대 방향(회원이 연 시작 · 로그인 · 가입 화면 → 홈 또는 `?next=`)은 첫 진입 레이아웃의 `features/auth/guest-only-gate.tsx` 다. 같은 이유로 `useAuthSettled()` 가 true 일 때 판단하고, **화면에 닿을 때의 상태로 한 번만** 판단한다 — 그 화면에서 회원이 되는 것(로그인 성공)은 화면이 스스로 이동하므로 두 이동이 겹치지 않게 끼어들지 않는다(docs/design/SCREENS.md "첫 진입").
- **router 내비게이션이 대기 중일 때 `history.replaceState` · `pushState` 를 부르면 Next 가 그 내비게이션을 버린다 — 가드가 보낼 곳이 있으면 주소 정리를 하지 않는다.** 원시 history 변경이 Next 의 복원(ACTION_RESTORE)을 일으켜 대기 중인 `router.replace` 가 버려진다(프로덕션 빌드에서 재현). 같은 그림에서 주소 쿼리를 정리하는 화면(홈의 `?report=` · 내 정보의 `?confirm=` 정리)은 `useRequiredStepsGate` · `useRequiredStepsTarget`(`features/me/member-gate.ts`)이 돌려준 보낼 곳이 있으면 정리를 건너뛴다.
- **레이아웃 · 정적 라우트에서 `useSearchParams` 를 읽는 클라이언트 컴포넌트는 `<Suspense>` 로 감싼다.** 감싸지 않으면 `next build` 의 정적 생성이 `missing-suspense-with-csr-bailout` 으로 멈춘다(dev 서버에서는 드러나지 않는다). 지금은 CSP nonce 때문에 모든 화면이 동적이라 빌드가 멈추지 않지만, 정적 렌더링으로 돌아갈 수 있게 그대로 지킨다(아래 "보안 헤더 · CSP"). 하이드레이션 뒤에야 그리는 화면은 대체 그림을 비워 둔다(`app/me/layout.tsx` 의 가드, `app/(onboarding)/terms/reconsent/page.tsx`). 페이지가 `searchParams` 를 await 하면 동적 라우트라 필요 없다.
- **돌아갈 곳(`?next=`)은 허용 목록 안의 경로만 받는다.** 가드가 다른 화면으로 보냈다가 돌려보낼 때 `?next=` 를 쓰고, 받는 쪽은 `features/auth/required-steps.ts` 의 `safeNextPath` 로 정확히 같은 경로(`NEXT_PATHS`: `/` · `/me` · `/me/devices` · `/me/password` · `/me/region` · `/me/nickname` · `/me/reports` · `/me/interest-regions` · `/me/notifications` · `/admin/review`)일 때만 따른다. 머리줄 동네 이름이 여는 둘러볼 동네 고르기(`/browse/region?next=`)는 따로 둔 허용 목록(`features/onboarding/browse-return.ts` 의 `BROWSE_NEXT_PATHS`, 머리줄에 동네 이름이 있는 화면)을 같은 방식으로 받는다(#141). 로그인 화면(`/login` → `/login/email`)도 `features/auth/login-return.ts` 로 `?next=` 를 받아 로그인 뒤 그곳으로 간다(내 정보 · 로그인한 기기 · 비밀번호 · 내 동네 · 닉네임 · 최근 보고 내역 · 관심 동네 · 알림 설정의 비회원 가드 `useMemberGate({ next })` 가 씀 — `next` 는 생략할 수 없다, #140. 운영자 화면 가드 `features/admin/admin-gate.tsx` 도 같은 가드로 비회원을 보낸다, #219). 보고하려던 로그인은 `?intent=report` 를 더 받아 로그인 뒤 같은 동네 홈의 보고 진입(`/?region=…&report=start`)으로 간다 — 받는 값은 `report` 하나, 돌아갈 곳이 홈일 때만 받고(그 밖이면 버림) `NEXT_PATHS` 규칙은 그대로이며, 회원 상태에 맞는 시트는 홈의 `guardReportEntry` 가 고친다(#136). 로그인 결과에 조건(약관 재동의 · 동네 다시 고르기)이 남으면 홈의 조건 가드가 보고 진입(`?report=`)을 조건 화면의 `?intent=report` 로 바꿔 싣고, 조건 화면을 **마친** 뒤(`targetAfter`) 남은 조건이 없으면 같은 동네 홈의 보고 진입으로 간다 — 조건 화면에 조건 없이 닿아 내보낼 때(`stepTarget`)는 보고 진입을 붙이지 않는다(브라우저 뒤로를 붙잡지 않게, #140). 회원이 그 로그인 화면에 닿으면(사실상 로그인 성공 뒤 브라우저 뒤로) 첫 진입 가드는 보고 진입을 붙이지 않고 홈으로만 보낸다 — 뒤로 가려는 사람을 붙잡지 않는다. 로그인으로 넘기는 쿼리는 `next` 와 둘러보기 동네뿐이고 목 덮어쓰기는 넘기지 않는다(로그인이 세션을 바꾸므로). 쿼리 · `#` 가 붙었거나 다른 오리진(`//…` · `https://…`)이면 홈으로 보낸다(오픈 리다이렉트 방지). 함께 넘길 쿼리는 둘러보기 동네(`region`)와 QA 용 목 덮어쓰기(`mock-auth` · `mock-provider` · `mock-required`, 목데이터 모드에서만 듣는다 — 아래 "데이터 출처")뿐이다(`carriedParams`). 둘러볼 동네 고르기는 여기에 알림 덮어쓰기(`mock-push`)를 더 넘긴다(`browse-return.ts`, #195 — 알림 설정으로 돌아가도 같은 기기로 보이게). 새 화면을 가드에 걸면 목록에 더한다.
- **페이지를 새로 열어도 이을 돌아갈 곳은 `features/auth/login-return-store.ts` 하나로 둔다**(#140). 로그인 화면 · 로그인 안내 시트가 받은 돌아갈 곳(`next` · `region` · `intent`)을 가입(이메일 · 카카오) · 카카오 로그인 · 비밀번호 재설정을 거치는 동안 들고 간다 — 이 흐름들은 단계마다 주소 쿼리를 넘기지 않고, 카카오는 문서를 옮겼다 돌아와 메모리가 빈다.
  - **떠날 때 쓴다**(`saveLoginReturn`): 카카오로 계속하기(`useKakaoStart(…).start(loginReturn)` — 로그인 방법 고르기 · 로그인 안내 시트 · 다른 카카오 계정), 이메일로 가입하기 · 비밀번호를 잊었어요(로그인 방법 고르기 · 이메일 로그인). 들고 갈 것이 없으면 지운다(앞서 그만둔 흐름의 값이 끼어들지 않게). **마칠 때 읽고 지운다**(`takeLoginReturn`): 가입 마무리 S02-4(`나중에 할게요` 는 보고 진입을 빼고 — 미룬 동의 시트를 다시 열지 않게), 카카오 로그인됨 · 계정 연결 성공(`afterKakaoLoginPath`), 비밀번호 재설정 성공(`/login/email?reason=reset-done` 에 쿼리로 붙임). 카카오 실패 · 가입된 이메일로 로그인처럼 로그인 화면으로 돌려보낼 때는 지우지 않고 그 주소에 쿼리로 다시 싣는다(`withSavedLoginReturn`) — 이렇게 온 로그인 방법 고르기의 뒤로가 그만둔 단계로 되돌아가지 않게, 그 화면은 바로 앞 기록이 흐름 단계(`features/onboarding/paths.ts` 의 `isFlowStepPath`)면 시작 화면으로 바꿔 간다(`useNavTrail().goBack` 은 후보 목록 대신 바로 앞 경로를 받는 함수도 받는다). 이메일 로그인에 성공하면 지운다. 회원이 비밀번호 변경의 `비밀번호를 잊었어요` 로 재설정에 가면 돌아갈 곳을 내 정보로 둔다.
  - 저장 위치는 모듈 변수(같은 문서 안 이동 — 첫 진입 레이아웃 밖의 시트에서 들어와도, 저장소가 막혀도 남는다) + `sessionStorage`(새로고침 · 카카오 왕복). 읽을 때는 모듈 변수가 먼저다. `sessionStorage` 는 그 탭에만 살고 탭을 닫으면 사라져 다른 탭 · 다음 방문에 새지 않으며, 서버가 읽을 일이 없어 쿠키로 두지 않는다.
  - 키 `sc_login_return`, 값 `{"v":1,"next","region","intent","savedAt"}` — **허용 목록 경로 · 행정동 코드(8자리 숫자) · `report` 만** 담는다(이메일 · 토큰 · 건강 정보 금지). 수명 30분(카카오 가입표 · 이메일 인증 표시의 서버 수명과 같다) — 지난 값 · 앞으로의 시각은 버린다.
  - 읽을 때 다시 검증한다: `next` 는 `safeNextPath`, 동네는 모양, `intent` 는 `report` 이고 홈일 때만. 모양 · 판이 다르면 통째로 버리고 지운다. 저장소가 없거나 예외면 모듈 변수만, 그것도 없으면(새로고침 + 저장소 없음) 홈이다 — 흐름은 그대로 된다. 이메일 가입 단계를 새로고침하면 가입 초안(Provider)은 비어 처음 단계로 돌아가지만 돌아갈 곳은 저장소에서 살아 가입을 다시 마치면 그곳으로 간다.
- **앱 밖 주소로 문서를 옮기는 일은 `src/lib/location.ts` 의 `assignLocation` 하나로 한다**(카카오 인가 화면, #167). 옮기기 전에 부르는 쪽이 주소의 오리진 · 경로를 허용 목록으로 확인한다(`features/auth/kakao-client.ts` 의 `isKakaoAuthorizeUrl` — 오픈 리다이렉트 방지). 돌아오는 콜백(`/login/kakao/callback`)은 주소에 실려 온 값(인가 코드 — proxy 가 쿼리를 fragment 로 옮겨 보낸다, 아래 "보안 헤더 · CSP" 의 "카카오 콜백")을 읽자마자 `history.replaceState` 로 지운다 — 마운트 effect 에서 바로가 아니라 `setTimeout(0)` 뒤에, 라우터 이동을 걸기 **전에** 지운다(위 두 규칙). 카카오로 떠나기 전에 둔 돌아갈 곳을 콜백(로그인됨) · 계정 연결 · 카카오 가입 마무리가 읽는다(위 "페이지를 새로 열어도 이을 돌아갈 곳").
- **화면 위에 뜨는 시트 · 대화상자의 열림 상태는 주소 쿼리에 둔다** (`docs/design/SCREENS.md` 의 제안 라우트, 예: 판단 기준 `/?explain=1`). 새로고침 · 공유해도 같은 화면이 열린다. `src/lib/use-modal-param.ts` 를 쓴다. 단계마다 `push` 로 기록을 쌓아 휴대폰 뒤로 가기가 이전 단계 · 닫기로 이어지게 하고, 보낸 뒤 완료처럼 되돌아오면 안 되는 단계는 `replace` 로 바꾼다(`remove` 로 다른 쿼리를 같은 기록 항목에서 함께 지울 수 있다). `close` 는 이 훅이 쌓은 깊이만큼만 되돌린다 — 깊이는 `history.state` 에 두어 뒤로 가기로 단계를 되돌린 뒤 닫아도 홈 앞까지만 간다. 주소로 바로 들어와 쌓은 기록이 없으면 쿼리만 지운다. `router.push` 는 서버에 화면을 다시 요청하므로 쓰지 않는다.
- **같은 문서 안 `#` 링크(`<a href="#id">`)를 쓰지 않는다.** Next 가 모르는 기록 항목(`history.state` 가 null)이 생겨, 그 뒤 연 시트 · 대화상자의 닫기(`history.go(-1)`)가 그 항목으로 돌아가고 Next 가 무시해 첫 닫기에 닫히지 않는다. 섹션 바로가기는 버튼으로 `scrollIntoView` 한 뒤 제목(`tabIndex={-1}`)에 포커스를 준다(`features/me/me-screen.tsx`).
- **마운트 effect 에서 history 를 바꾸지 않는다.** 하이드레이션 첫 커밋에서는 Next 가 아직 history 를 감싸지 않는다(최상위 라우터 effect 보다 자식 effect 가 먼저 돈다) — 그때 바꾼 주소는 `useSearchParams` 가 모른다. 미뤄야 하면 `setTimeout(0)` 으로 미루고 cleanup 에서 취소한다(`features/home/home-screen.tsx` 의 보고 진입 정리). 열림처럼 그 값으로 그리는 것은 바뀔 값으로 미리 계산해 첫 그림부터 맞춘다.
- 서버에 보내는 동작(보고 보내기 · 고치기 · 되돌리기)은 연동 전에도 `features/<도메인>/*-client.ts` 에 Promise 를 돌려주는 함수로 둔다. 연동 때 함수 안을 `src/lib/api/` 호출로 바꾸고 화면은 출처 인자 · 결과 갈래만 더한다 (`features/report/report-client.ts`, #165 에서 연동).
- API 연동 전 화면은 `features/<도메인>/mock.ts` 의 목 데이터로 만든다. 목 데이터의 기본값은 `자료 부족` 처럼 수치를 지어내지 않는 상태로 둔다. 연동 이슈에서는 목 데이터를 지우지 않고 데이터 출처 장치(아래 "API 계층" 의 "데이터 출처")의 목 갈래로 남긴다.

- 화면 코드(`app/`, `src/features/`, `src/components/`)에서 `fetch` 를 직접 부르지 않는다. API 호출은 `src/lib/api/` 로 모은다 (ESLint 로 막는다, 아래 "API 계층").
- 공개 환경변수는 `src/lib/env.client.ts` 에서만 읽는다. `process.env.NEXT_PUBLIC_X` 는 리터럴로 읽어야 빌드 때 치환된다.
- 목록은 `.env.example` 에 있다. 로컬 개발도 dev 게이트웨이(`https://api-dev.sneezecast.com`)를 쓴다.
- `localStorage` · `sessionStorage` 는 ESLint 가 막는다. 건강·증상 정보는 민감정보라 브라우저에 아무렇게나 남기지 않는다. 기기 토큰처럼 꼭 필요한 값은 저장 모듈 하나로 모으고 그 줄에 근거 주석을 남긴다. 지금 쓰는 곳은 로그인 뒤 돌아갈 곳(`features/auth/login-return-store.ts`, `sessionStorage`, 위 "페이지를 새로 열어도 이을 돌아갈 곳") 하나다.

## API 계층

백엔드 호출은 `src/lib/api/` 의 얇은 `fetch` 래퍼 하나로 한다. 데이터 패칭 라이브러리(TanStack Query 등)는 쓰지 않는다 — 화면 수가 적고 대부분 한 번 읽고 끝나는 요청이라, 캐시 · 재시도 정책을 라이브러리에 맡기기보다 래퍼 하나에 두는 편이 단순하다. 계약은 [api-contract-draft.md](api-contract-draft.md) 에 있다.

| 모듈              | 하는 일                                                                                                                        |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `client.ts`       | `apiRequest<T>(path, { method, query, body, auth, signal })` — 주소 결합 · JSON · 타임아웃 · 봉투 풀기                         |
| `envelope.ts`     | 공통 응답 봉투 `{ dataHeader: { success, resultCode, resultMessage, fieldErrors }, dataBody }` 읽기                            |
| `api-error.ts`    | `ApiError { status, code, message, fieldErrors }`, 일시 장애 코드 `UNAVAILABLE`                                                |
| `error-kind.ts`   | `classifyApiError` — 재발급 · 로그인 필요 · 권한 없음 · 재로그인 · 재발급 경합 · 일시 장애 · 그 밖                             |
| `access-token.ts` | access token 공급자 · 갈아 끼우기를 끼우는 자리(`setAccessTokenProvider` · `setAccessTokenRefresher`). 토큰 자체는 들지 않는다 |

- **도메인 클라이언트(`features/<도메인>/*-client.ts`)만 `apiRequest` 를 부른다.** 화면은 도메인 클라이언트의 함수를 부르고, 응답을 화면 모델로 옮기는 일도 도메인 클라이언트가 한다. 래퍼는 `dataBody` 모양을 검사하지 않는다.
- 주소는 `clientEnv.apiBaseUrl` + `/api/...` 경로다. 경로가 `/` 로 시작하지 않거나 다른 오리진이면 보내지 않는다(토큰이 다른 곳으로 새지 않게).
- 모든 요청은 `credentials: 'include'`(refresh 쿠키) · `cache: 'no-store'`(회원별 · 주별 응답을 캐시하지 않음) 다.
- 타임아웃은 12초다 — 게이트웨이 업스트림 상한(10초)보다 길게 두어 게이트웨이의 `GATEWAY_004`(504) 봉투를 먼저 받는다. 호출한 쪽의 취소(`signal`)는 일시 장애로 바꾸지 않고 그 사유를 그대로 던진다.
- 결과: 성공 봉투 → `dataBody`(본문 없는 API 는 null). 실패 봉투 → `ApiError`(서버 `resultCode` · `resultMessage` · `fieldErrors`, HTTP 상태). 봉투가 없는 응답(Spring 기본 오류 · HTML · 빈 본문) · 네트워크 실패 · 타임아웃 → `ApiError` 코드 `UNAVAILABLE`(응답을 못 받았으면 상태 0).
- 검증 오류는 `fieldErrors` 의 `field` 별 첫 오류를 그 입력 옆에 보인다. 서버가 순서를 고정해 준다(`resultCode` 는 첫 오류).
- **access token 은 메모리에만 둔다.** 브라우저 저장소 · 주소에 두지 않는다. refresh 토큰은 HttpOnly 쿠키라 화면이 다루지 않는다. 세션 저장소(아래 "세션 저장소")가 `setAccessTokenProvider` · `setAccessTokenRefresher` 로 자신을 끼운다. 공급자가 토큰을 주면 래퍼가 `Authorization: Bearer` 를 싣는다.
- **재발급(`POST /api/v1/auth/token/reissue`)은 `auth: false` 로 Authorization 없이 부른다.** 게이트웨이와 auth 필터는 경로와 무관하게 헤더가 있으면 access 를 검사해, 만료된 access 를 실으면 refresh 가 멀쩡해도 `SECURITY_002` 로 끝난다.
- 401 처리: 토큰을 실어 보낸 요청이 `reissue`(`SECURITY_002/003/004/005/007`)로 거절되면 `client.ts` 가 갈아 끼우기(`setAccessTokenRefresher` 로 끼운 함수)로 새 토큰을 받아 **같은 요청을 한 번만** 다시 보낸다(주소 · 메서드 · 바디 같음, 다시 받은 오류는 그대로 던짐). 토큰을 싣지 않은 요청 · `SECURITY_001` · 403 · `auth: false` 는 다시 보내지 않는다 — 재발급이 재발급을 부르는 고리가 없다. 갈아 끼우기가 없거나 null 을 주면 원래 오류를 던진다. 화면 코드는 401 을 따로 다루지 않는다.
- 로그를 남기지 않는다. 요청 바디 · 토큰 · 응답을 `console` 에 찍지 않고, 비밀번호 · 토큰 · 인증 코드 · 건강 정보는 `query` 가 아니라 `body` 로 보낸다.
- 테스트는 `vi.stubGlobal('fetch', …)` 로 가짜 `fetch` 를 끼운다(`src/lib/api/client.test.ts`). 도메인 클라이언트 테스트는 `apiRequest` 를 `vi.mock` 으로 바꾼다.

### 세션 저장소 (실데이터 모드)

`src/lib/session/` 이 로그인 · 재발급 응답(`AuthToken { memberId, role, accessToken, accessTokenExpiresIn, pendingConsents, reportWritable }`)을 들고 API 계층에 토큰을 준다. 루트 레이아웃의 `features/auth/session-bootstrap.tsx` 가 켠다.

| 모듈               | 하는 일                                                                                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `session-store.ts` | 스냅숏(`idle` · `restoring` · `guest` · `member`+요약) · `setSession` · `clearSession` · `restoreSession` · `revalidateSession`(bfcache 복원) · `startSession`(슬롯 설치) |
| `session-hint.ts`  | 힌트 쿠키 `sc_session` 읽기 · 쓰기                                                                                                                                        |
| `session-sync.ts`  | 탭 사이 알림(`BroadcastChannel` `sneezecast:session`) · 재발급 잠금(`navigator.locks` `sneezecast:session-reissue`). 없으면 강등. 알림 종류는 아래 목록                   |
| `use-session.ts`   | `useSession()` — 서버 · 하이드레이션 첫 그림은 늘 `idle`                                                                                                                  |

- **탭 사이 알림 종류**(`SessionMessage`). 받은 값은 `parseSessionMessage` 로 모양을 확인하고, 모양이 다르거나 **모르는 종류는 버린다** — 배포 중 판이 다른 탭이 섞여도 서로 모르는 알림을 무시한다. 새 종류를 더하면 이 목록을 고친다.
  - `signed-in { token }`: 로그인 · 재발급 결과. 받은 탭은 재발급 없이 그 토큰을 쓴다
  - `signed-out { reason: logout | expired | withdrawn }`: 로그아웃 · 만료 · 탈퇴. 받은 탭은 조용히 비회원이 된다
  - `region-changed { memberId }`(#190): 실데이터 `saveRegion` 성공. 세션 저장소가 이 탭의 회원과 같을 때만 회원 정보 저장소에 넘기고(`subscribeMemberRegionChanged`), 저장소가 내 동네를 다시 읽는다. **동네 코드 · 이름은 싣지 않는다**(받은 탭은 서버 값만 쓴다). `memberId` 는 서버의 불투명한 번호이고 같은 통로의 `signed-in` 이 이미 싣는 값이라 새로 드러나는 정보가 없다
- **토큰은 `session-store.ts` 의 지역 변수에만 둔다.** 화면이 읽는 스냅숏에는 회원 요약(아이디 · 역할 · 재동의 항목 · 보고 가능)만 있다. 쿠키 · 브라우저 저장소 · 주소 · `console` 에 남기지 않고, 같은 오리진의 다른 탭에만 알림으로 넘긴다.
- 만료 시각은 `accessTokenExpiresIn`(초)으로 정한다(JWT 를 풀지 않음). 요청 직전에 만료 30초 전(`ACCESS_EXPIRY_MARGIN_MS`)이면 먼저 재발급한다 — 타이머는 없다. 재발급은 `POST /api/v1/auth/token/reissue` 를 `auth: false` 로 부른다.
- 재발급은 탭 안에서 하나로 묶고(동시 401 여러 건 → 재발급 1회), 탭 사이는 잠금으로 줄 세운다. 잠금을 잡았을 때 그사이 다른 탭 알림으로 세션이 바뀌었으면 재발급하지 않고 그 토큰을 쓴다. 응답을 기다리는 동안 로그아웃했으면 늦은 응답을 버린다.
- 재발급 결과: 경합(`reissue-conflict`, `AUTH_016`)이면 `REISSUE_CONFLICT_RETRY_DELAY_MS`(300ms — 이긴 탭의 새 refresh 쿠키가 반영될 틈)를 기다려 한 번 다시 한다. 재로그인(`relogin`, `AUTH_014/015`) · 탈퇴 · 정지(`MEMBER_002/003`)면 세션을 비우고 다른 탭에 알리며 `notifySessionExpired()`(`src/lib/session-expiry.ts`)를 부른다. 두 번째 경합은 **이 탭만** 비우고 다른 탭에 알리지 않는다(이긴 탭의 세션은 멀쩡할 수 있다 — 잠금이 없는 환경).
- 재발급이 일시 장애(`unavailable`)이거나 분류에 없는 오류면 세션을 끝내지 않는다 — 회원 요약은 두고 토큰만 버린다. 세션 중이면 공급자 · 갈아 끼우기가 일시 장애(`UNAVAILABLE`)를 던져 요청이 "잠시 뒤 다시" 로 끝난다(토큰 없이 보내 `SECURITY_001` 로 보이지 않게). 다음 요청이 다시 재발급한다.
- **새로고침 복원**: refresh 쿠키는 게이트웨이 호스트 · `Path=/api/v1/auth` 전용 HttpOnly 라 화면이 있는지 모른다. 로그인 · 재발급에 성공하면 1st-party 힌트 쿠키 **`sc_session=1`**(`Path=/` · `SameSite=Lax` · HTTPS 면 `Secure` · 14일, 값은 고정 `1`)을 남기고, 로그아웃 · 만료 · 재로그인 응답이면 지운다. 앱을 열 때 실데이터 모드이고 힌트가 있을 때만 재발급을 한 번 한다(`restoring` → `member`). `SessionBootstrap` 은 하이드레이션 커밋의 effect 에서 출처 쿠키를 직접 읽어(`readBrowserDataSource`) 바로 시작한다 — 훅 값(첫 그림은 서버 기본값)을 기다리면 `idle` 동안 나간 회원 요청이 토큰 없이 나간다. 힌트가 없으면 요청 없이 `guest` 다. 복원이 재로그인 · 탈퇴 · 정지 · 두 번째 경합으로 끝나면 알리지 않고(방송도 없음) 힌트를 지우며, 일시 장애면 힌트를 남긴 채 `guest` 로 보인다. 복원 중 요청은 복원을 기다려 그 토큰을 싣는다.
- 만료 알림(`notifySessionExpired`)은 이 탭에 세션이 있었을 때만 부른다. 다른 탭의 로그아웃 · 만료 알림을 받으면 조용히 `guest` 가 된다.
- **뒤로 가기 캐시 복원**(#186): 화면이 bfcache 에서 되살아나면(`pageshow` 의 `persisted`) `SessionBootstrap` 이 실데이터 모드에서 `revalidateSession()` 을 부른다. 회원이었으면 바로 `restoring` 으로 가리고(옛 토큰도 버림) 재발급을 한 번 해 다시 확인한다 — 결과 처리는 새로고침 복원과 같다. 근거는 아래 "뒤로 가기 캐시(bfcache)".
- 화면은 `features/auth/use-auth.ts` 의 `useAuth()`(회원 상태) · `useAuthSettled()`(정해졌는지)로 읽는다. `useMockAuth` 는 같은 훅의 옛 이름이다(이름 정리는 후속).
- **실데이터 모드에서 회원이 되는 길은 이메일 로그인(#163)이다.** `loginWithEmail(…, 'api')` 이 응답을 `setSession` 에 넣고, `logout(…, 'api')` 이 `clearSession('logout')` 을 부른다(실패 정책은 [api-contract-draft.md](api-contract-draft.md) "인증" 의 "프론트 연동 (#163)"). 비밀번호 재설정 · 변경과 로그인한 기기는 #166 에서 연동했다 — 재설정 성공(재설정한 이메일이 이 탭 계정의 이메일과 같을 때 — 다르면 그대로, 모르면 `logout('api')`) · 이 기기 세션 로그아웃(`revokeSession` 의 `current`)은 서버가 이 기기 세션도 끝내므로 `clearSession('logout')`, 다른 기기 모두 로그아웃이 `AUTH_014`(이 기기 세션을 서버가 모름)면 `clearSession('expired')` 이고, 비밀번호 변경은 이 기기 세션을 그대로 둔다(계약 · 오류 매핑은 [api-contract-draft.md](api-contract-draft.md) "인증" · "회원" 의 "프론트 연동 (#166)"). 카카오 로그인 · 카카오 가입 · 계정 연결은 #167 에서 연동했다 — 콜백의 `LOGGED_IN` · 카카오 가입 · 연결 응답을 `setSession` 에 넣는다(`features/auth/kakao-client.ts` · `signup` 카카오 갈래, 계약 · 오류 매핑은 [api-contract-draft.md](api-contract-draft.md) "인증" 의 "프론트 연동 (#167)"). 건강정보 동의 · 약관 재동의 · 건강정보 동의 철회는 #168 에서 연동했다 — 동의는 응답 뒤 `refreshSession()` 으로 세션 요약(`reportWritable` · `pendingConsents`)을 다시 받고(동의만으로는 access 의 scope 가 그대로다), 철회는 서버가 모든 기기를 로그아웃하므로 성공하면 `clearSession('logout')` 이다(계약 · 오류 매핑은 [api-contract-draft.md](api-contract-draft.md) "회원" 의 "프론트 연동 (#168)"). 탈퇴는 아직 출처와 무관하게 목이다(#169). 로그아웃 중 재발급이 재로그인으로 끝나 세션이 이미 비었으면 다시 비우지 않고, 성공 · 세션 사라짐 모두 만료 진행 표시(`clearSessionExpiring`)를 끈다.
- **회원 정보 저장소**(`features/auth/member-info.ts`, #164): 세션이 회원이 되면(`memberId` 가 바뀔 때만) 내 정보(`GET /api/v1/members/me`) · 내 동네(`GET /api/v1/members/me/region`)를 한 번씩 읽어 메모리에 두고, 비회원이 되면 바로 지운다(늦은 응답은 버린다). 두 요청은 따로 `loading` · `ready` · `failed` 이고 `retryMemberInfo()` 는 실패한 쪽만 다시 읽는다. 서버 응답이 읽은 값과 어긋났을 때는 지금 값을 둔 채 다시 읽는다(`reloadMemberRegion()` #165 · `reloadMemberInfo()` #166 — 비밀번호 변경이 `MEMBER_007`). **내 동네는 다른 곳에서 바뀌었을 수 있을 때도 같은 규칙으로 다시 읽는다**(#190 — 보고가 이 탭이 든 내 동네 코드로 나가므로 낡으면 고르지 않은 동네의 집계에 들어간다): ① 같은 브라우저의 다른 탭이 실데이터로 저장하면 탭 사이 알림 `region-changed`(위 "세션 저장소" 의 알림 종류)를 받아 다시 읽는다 — 세션 저장소가 이 탭의 회원과 같을 때만 넘긴다(`subscribeMemberRegionChanged`). 지금 값이 낡았음이 확정이라 **지금 값을 두지 않고 `loading` 으로** 다시 읽고, 실패하면 `failed` 다 — 그동안 보고 흐름은 보내기를 막는다(읽는 중 · 실패 안내, 실패면 다시 읽음). 처음 읽는 중이어도 그 조회가 저장보다 먼저 나갔을 수 있어 새로 보낸다(앞 응답은 버림). 저장한 탭은 응답을 이미 넣었으니 다시 읽지 않는다. 다시 읽는 동안 둘러보기 동네가 없는 머리줄은 내 동네를 모를 때처럼 서버가 준 이름(지금은 목 예시)이 잠깐 보인다. ② 다른 기기에서 바꾼 경우는 화면이 다시 보일 때(`visibilitychange` → visible · `focus`) 실데이터 모드의 회원이면 다시 읽되, 마지막으로 읽은(조회를 보낸 · 저장 응답을 넣은) 때부터 60초(`REGION_REFRESH_INTERVAL_MS`)가 지났을 때만이다. 이때는 낡았는지 모르므로 `reloadMemberRegion()` 규칙대로 지금 값을 둔다. 리스너는 `startMemberInfo` 의 정리에서 뗀다. 보고를 보내기 직전에는 따로 확인하지 않는다. 한계: 같은 회원이 두 탭에서 거의 동시에 저장하면 늦게 응답받은 탭이 서버 값과 잠깐 어긋날 수 있고(자기 저장 응답이 상대 알림의 다시 읽기를 덮는다), 화면이 다시 보일 때(60초 제한) 바로잡힌다. `SessionBootstrap` 이 `startMemberInfo()` 로 세션에 잇는다. 화면은 출처를 가리는 훅(`useMockProfile` · `useMockProfileStatus` · `useMemberRegion` · `useMemberRegionStatus` · `useMemberRequirements`)으로만 읽는다 — 실데이터에서 읽기 전 · 실패면 프로필 · 내 동네가 null 이고 **예시 값으로 채우지 않는다.** 내 정보가 `MEMBER_004` 면 `clearSession('withdrawn')`, 탈퇴 · 정지(`MEMBER_002` · `003`)면 재발급과 같은 `clearSession('expired')` 다. 실데이터 `saveRegion` 은 세션이 회원이 아니면 요청 없이 거부한다. 내 동네 저장(`saveRegion(…, 'api')`)은 응답을 저장소에 바로 넣고 다른 탭에 알린다(`broadcastMemberRegionChanged`, 계약 · 오류 매핑은 [api-contract-draft.md](api-contract-draft.md) "회원"). 닉네임 바꾸기(`updateNickname(…, 'api')`, #192)도 응답(내 정보)을 같은 규칙으로 바로 넣고(`setMemberInfo` — 먼저 나간 조회 · 다시 읽기 응답은 버림) 다른 탭에는 알리지 않는다(닉네임은 보고 · 집계에 쓰이지 않는다).
- **이번 주 보고 저장소**(`features/report/current-report.ts`, #165): 회원 정보 저장소와 같은 모양으로 세션에 잇는다. 세션이 회원이고 `reportWritable` 이 true 일 때만 `GET /api/v1/reports/current` 를 읽고(false 면 요청 없이 비움 — 403 을 받으러 가지 않는다), `memberId` 가 바뀌거나 `reportWritable` 이 false → true 가 되면 다시 읽으며, 비회원 · 보고할 수 없게 되면 지운다. single-flight · 늦은 응답 버림 · 보내기 · 되돌리기 결과를 바로 넣음 · 화면이 다시 보일 때(`visibilitychange` · `focus`) 읽은 값의 주가 KST 지금 주와 다르면 다시 읽음(리스너는 `startCurrentReport` 의 정리에서 뗀다). 화면은 `useSubmittedReport()`(읽는 중 · 실패 · 미보고 모두 null) · `useSubmittedReportStatus()` 로 읽는다. 보고(민감정보)는 메모리에만 둔다. 서비스가 보고 권한으로 거절하면(`SECURITY_006`) `refreshSession()`(세션 저장소 — 재발급 한 번으로 회원 요약을 맞춤, 던지지 않음)을 부르고, 폐지 · 없는 동네로 거절하면 `reloadMemberRegion()`(회원 정보 저장소 — 읽은 내 동네를 지금 값을 둔 채 다시 읽음)을 부른다(계약 · 오류 매핑은 [api-contract-draft.md](api-contract-draft.md) "주간 보고").
- 한계(로그인 연동 뒤): FE 로컬(`http://localhost`)은 교차 사이트라 refresh 쿠키가 실리지 않는다 — 로그인은 되지만 새로고침하면 재발급이 `AUTH_014` 로 끝나 `guest` 다. 새로고침 복원은 dev 웹에서 확인한다.

### 데이터 출처 (실데이터 · 목데이터)

개발 서버가 없거나 백엔드가 준비되지 않은 동안에도 화면을 확인하고, 준비되면 같은 화면으로 실제 응답을 확인하려고 **도메인 클라이언트가 출처(`api` | `mock`)를 인자로 받아** 백엔드 호출과 목 데이터 중 하나로 답한다. 행정동(`features/region/region-client.ts`)이 처음이고, **세션 · 로그인 · 보고 등 새로 연동하는 도메인도 이 장치를 쓴다**(목 갈래를 지우지 않는다).

| 모듈                                    | 하는 일                                                                                                                 |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `src/lib/data-source.ts`                | `DataSource` · 쿠키 이름 · 해석(`resolveDataSource`) · 전환 허용 목록 · 브라우저 쿠키 읽기/쓰기. 서버 · 클라이언트 공용 |
| `src/lib/data-source.server.ts`         | 서버 컴포넌트 전용 `readServerDataSource()` (`next/headers` 의 `cookies()`)                                             |
| `src/lib/use-data-source.ts`            | 클라이언트 컴포넌트의 `useDataSource()`. 토글로 바꾸면 바로 따라간다                                                    |
| `src/components/data-source-toggle.tsx` | 화면 오른쪽 아래 토글(개발용). 루트 레이아웃이 전환할 수 있는 사이트에서만 body 마지막에 그린다                         |

- 고른 값은 1st-party 쿠키 **`sc_data_source=api|mock`** 에 둔다. `Path=/` · `SameSite=Lax` · 1년 · HTTPS 면 `Secure`. 화면 토글이 읽고 써야 해 `HttpOnly` 가 아니다. 출처 이름뿐이라 개인을 알아보는 값이 없다.
- 쿠키가 없거나 모르는 값이면 기본값 — 공개 환경변수 `NEXT_PUBLIC_DATA_SOURCE`(`.env.example`), 그것도 없거나 모르는 값이면 `mock` 이다.
- **전환은 허용 목록의 사이트에서만 된다(`isDataSourceSwitchable`).** `clientEnv.siteUrl` 이 dev 웹 `https://dev.sneezecast.com` 이거나, `http:` 이면서 호스트가 `localhost` · `127.0.0.1` · `[::1]` · `*.localhost` 일 때만이다. 그 밖(운영 · 빈 값 · 오타 · 주소가 아님)은 쿠키 · 환경변수를 무시하고 늘 `api` 이고 토글을 그리지 않는다. 막을 곳이 아니라 열 곳을 적는다 — 운영 빌드에 사이트 주소를 잘못 넣어도 토글이 열리지 않게 한다. 쿠키는 누구나 고칠 수 있어, 운영에서 목으로 바꿀 수 있으면 지어낸 수치 · 안내가 실제 정보처럼 보이고 목 회원 상태로 회원 화면이 열린다.
- **출처는 부르는 쪽이 정해 넘긴다.** 서버 페이지는 `readServerDataSource()`, 클라이언트 훅은 `useDataSource()` 로 읽어 도메인 클라이언트 함수에 넘긴다(`districtFromParam(value, source)` · `useDistrictSearch`). 도메인 클라이언트는 쿠키를 직접 읽지 않는다 — 서버 · 클라이언트 양쪽에서 같은 함수를 쓰고 테스트가 출처를 정해 부를 수 있게.
- **루트 레이아웃에서 `cookies()` 를 부르지 않는다** — 모든 라우트가 동적 렌더링이 된다. 지금은 CSP nonce 때문에 루트 레이아웃이 `connection()` 으로 이미 모든 화면을 동적으로 그리지만(아래 "보안 헤더 · CSP"), 정적 렌더링으로 돌아갈 길을 막지 않게 쿠키는 계속 페이지에서만 읽는다. 쿠키는 이미 동적인(`searchParams` 를 await 하는) 페이지에서만 읽는다. 그래서 루트 레이아웃의 토글과 `useDataSource()` 는 서버 그림 · 하이드레이션 첫 그림에서 기본값이고 그 뒤 쿠키 값을 읽는다(토글은 하이드레이션 뒤에만 그린다). 출처로 요청하는 effect 는 출처를 의존값에 넣는다.
- 토글은 `실데이터` · `목데이터` 두 버튼을 묶은 `role="group"`(이름 `데이터 출처`)이고 고른 쪽이 `aria-pressed="true"` 다. 보이는 글자가 접근성 이름이며(#188), 고른 버튼을 다시 눌러도 아무 일 없다. 다른 쪽을 누르면 쿠키를 쓰고 `router.refresh()` 로 서버 컴포넌트를 다시 그린다. 클라이언트 화면은 `useDataSource()` 로 따라간다.
- 레이아웃은 토글을 `components/lazy-data-source-toggle.tsx`(`lazyComponent`, 위 "공통 컴포넌트" 의 지연 로드)로 부른다. 정적으로 부르면 토글을 그리지 않는 운영 빌드에서도 코드가 모든 화면의 레이아웃 청크에 들어간다(#184). 서버 컴포넌트(레이아웃)에서 `next/dynamic` 을 불러서는 청크가 나뉘지 않는다.
- 토글은 body 마지막 자식이다(맨 앞이면 Tab 첫 포커스가 된다). z-index 를 주지 않는다 — z-index 가 있는 시트 · 버튼 묶음 · 가림막(z-10 · z-20)은 DOM 순서와 무관하게 토글 위에 온다. 모바일은 탭바 · 화면 아래 버튼 묶음보다 위(`bottom-dev-toggle`)에 띄운다.
- 백엔드에 아직 없는 API(BE 미정)는 출처와 무관하게 목이다(예: `listSuccessorDistricts`, 동네 안내 내용). 함수 주석에 적는다.
  - **예외 — 회원 개인의 건강 기록은 실데이터에서 목을 보이지 않는다**(#194). 지난 보고 내역(`features/report/report-history.ts` 의 `listPastReports`)은 실데이터면 요청 없이 `unavailable` 이고 화면은 아직 불러올 수 없다고 알린다. 건강 · 증상은 민감정보라 실제 회원에게 지어낸 과거 보고를 보이면 자기 기록으로 오해한다. 예시 이력은 목데이터 모드에서만 보인다.
  - **같은 예외 — 회원이 저장하는 값도 실데이터에서 목으로 저장하지 않는다**(#198). 관심 동네(`features/region/interest-region-client.ts`)는 실데이터면 요청 없이 `unavailable` 이고 화면은 목록 · 검색 없이 아직 저장할 수 없다고 알린다(내 정보 행의 수도 비운다). 목으로 받아 주면 실제 회원이 서버에 저장된 줄 알고, 새로고침 · 다른 기기에서 사라진 것을 보게 된다. 목 목록은 목데이터 모드에서만 보인다. 알림 설정(`features/notification/notification-settings-client.ts`, #195)도 같다 — 실데이터면 요청 없이 `unavailable` 이고 화면은 스위치를 꺼진 모양(누를 수 없음)으로 둔다. 켠 모양을 보이면 아직 보내지 않는 알림이 온다고 오해한다. 운영자 검토(`features/admin/admin-review-client.ts`, #219)도 같다 — 실데이터면 요청 없이 `unavailable` 이고 화면은 아직 준비하고 있다고 알린다. 목 후보를 실제 집계로 알고 발행을 눌러도 시민에게 아무것도 나가지 않는다.
- **회원 상태도 출처를 따른다.** 실데이터는 세션 저장소(위), 목데이터는 목 세션(`features/auth/auth-client.ts`)이다. 둘은 따로라 토글은 어느 쪽도 지우지 않고, 토글로 실데이터가 되면 `SessionBootstrap` 이 세션을 되살린다(여러 번 불러도 한 번).
- **QA 덮어쓰기(`?mock-auth=` · `?mock-provider=` · `?mock-required=` · `?mock-session=` · `?mock-role=`)는 목데이터 모드에서만 듣는다.** 실데이터에서 들으면 주소만으로 회원 · 동의 확인을 건너뛰고 운영자 화면이 열린다(`?mock-role=user|operator|admin` — 목 회원의 역할, #219 `useSessionRole`). 운영은 늘 실데이터라 덮어쓰기가 듣지 않는다. 홈 자료 덮어쓰기(`?mock=`) · 알림 덮어쓰기(`?mock-push=`)는 회원 확인과 무관해 그대로다. 최근 보고 내역의 `?mock-reports=empty`(#194)는 지난 보고 목록만 비우고, 실데이터에서는 지난 보고를 목으로 보이지 않으니(위 예외) 듣지 않는다. 관심 동네의 `?mock-interest-regions=empty|full|fail`(#198) · 알림 설정의 `?mock-notifications=on|fail`(#195) · 운영자 검토의 `?mock-admin=empty|fail|conflict`(#219)도 같다.

## 보안 헤더 · CSP

화면 응답에 CSP(Content-Security-Policy)를, 모든 응답에 브라우저 보안 헤더를 붙인다(#176). access token 은 메모리, refresh 는 HttpOnly 쿠키라 토큰을 훔칠 길은 좁지만, XSS 가 나면 메모리 토큰과 화면의 증상(민감정보)이 밖으로 나갈 수 있다. CSP 로 돌 수 있는 스크립트와 요청 · 이미지 대상을 묶어 그 피해를 줄인다. 구조(브라우저 → 게이트웨이)는 그대로다.

| 어디                                                      | 하는 일                                                                                                                                                                                                                                    |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `src/lib/security/content-security-policy.ts`             | 지시어 · nonce 만들기 · 게이트웨이 오리진 뽑기 · 요청 · 응답 헤더에 싣기                                                                                                                                                                   |
| `proxy.ts`                                                | 화면 요청마다 nonce 를 새로 만들어 CSP 를 싣는다. 카카오 콜백의 쿼리는 fragment 로 옮겨 303 으로 보낸다(아래). matcher 밖(`_next/` · `api/` · `icon*` · `apple-icon*` 로 시작하는 경로 · `app-icons/` · 점이 든 경로)은 CSP 를 받지 않는다 |
| `src/lib/security/security-headers.ts` + `next.config.ts` | 모든 경로(`/(.*)`, 빌드 산출물 포함)의 `nosniff` · `Referrer-Policy` · `Permissions-Policy` · `X-Frame-Options`, 콜백 예외. RSC 응답의 `Cache-Control`(`cache-headers.ts`, 아래 "뒤로 가기 캐시(bfcache)")                                 |
| `app/layout.tsx`                                          | `await connection()` — 모든 화면을 요청 때 그린다                                                                                                                                                                                          |

### 고른 방식: nonce + `'strict-dynamic'`

- **SRI(해시, `experimental.sri`)를 먼저 시험했다가 접었다.** `script-src 'self'`(`'unsafe-inline'` 없음) + SRI 로 `pnpm build` → `pnpm start` 를 헤드리스 Chrome 으로 열자(2026-10-03, Next 16.3.8 · Chrome 154) `/start` · `/` · `/login` 모두 Next 가 문서에 넣는 인라인 스크립트(`self.__next_f.push(…)` RSC 페이로드)가 `script-src-elem` 위반으로 막히고, React 오류 #412 와 함께 하이드레이션이 되지 않았다. SRI 는 외부 청크(`<script src>`)에 `integrity` 를 붙일 뿐 인라인 스크립트를 덮지 않는다.
- **인라인 스크립트 해시를 CSP 에 싣는 길도 없다.** 페이로드는 화면마다(동적 화면은 요청마다) 내용이 달라 해시가 바뀌는데, `headers()` 는 빌드 전에 정해지는 고정 값이라 화면별 해시를 실을 수 없다.
- 그래서 Next 가이드(`node_modules/next/dist/docs/01-app/02-guides/content-security-policy.md` "Nonces")대로 proxy 가 요청마다 128비트 nonce 를 만들어 CSP 를 **요청 헤더**와 **응답 헤더**에 싣는다. Next 는 렌더링 때 요청 헤더의 `script-src` 에서 nonce 를 읽어 자기 스크립트(프레임워크 · 화면 청크 · 인라인 페이로드)에 붙인다. 브라우저가 보낸 `Content-Security-Policy` 요청 헤더는 덮어쓴다(남기면 공격자가 아는 nonce 가 붙는다).
- `'strict-dynamic'`: nonce 가 붙은 스크립트가 넣은 스크립트(앱 안 이동 때 Next 가 받는 청크)도 돈다. 이를 아는 브라우저는 `'self'` 를 무시하고, 모르는 브라우저(CSP 2)는 `'self'` 로 같은 오리진 청크를 받는다.
- 확인(같은 방법, nonce 방식): 시작 · 로그인 · 이메일 로그인 · 가입 · 홈(둘러보기 · 보고 시트) · 지도 · 내 정보와 그 아래 화면 · 카카오 콜백 · 계정 연결 · 설치 안내 · 공식 정보 · 동네 안내 · 동네 고르기 · 비밀번호 재설정 · 없는 화면까지 30개 주소에서 위반 0 · 하이드레이션 정상. 데이터 출처 토글(쿠키 쓰기 · `router.refresh()`)과 앱 안 이동(새 청크 4개)도 위반이 없고, 다른 오리진 `fetch` · 외부 이미지 · 인라인 이벤트 처리기(`onerror=`)는 막혔다. `pnpm dev` 도 같은 화면에서 위반이 없다.

**성능 영향.** 화면이 모두 동적 렌더링이다 — 빌드 결과 정적(○) 18 → 3(아이콘 · 매니페스트 라우트만 남음), 동적(ƒ) 15 → 30, SSG(●, 매니페스트 아이콘) 4 그대로. 정적이던 15개 화면(시작 · 가입 단계 · 카카오 콜백 · 없는 화면 등)도 요청마다 서버가 그려 첫 응답이 늦어지고 서버 부하가 늘며, 문서를 CDN 에 둘 수 없다(동적 문서는 Next 가 `private, no-store` 로 보낸다). 빌드 산출물(`_next/static`)의 캐시는 그대로다. 화면의 서버 렌더링이 가벼워 영향은 작다고 보지만 측정은 #177(Lighthouse)에서 한다. 정적으로 돌아가려면 Next 가 인라인 페이로드를 해시로 덮을 수 있어야 한다 — 그때 다시 정한다.

### 지시어

| 지시어                      | 값                                                             | 근거                                                                                                                                                                                                                                                                     |
| --------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `default-src`               | `'self'`                                                       | 적지 않은 종류(`frame-src` · `media-src` 등)는 같은 오리진만                                                                                                                                                                                                             |
| `script-src`                | `'self' 'nonce-…' 'strict-dynamic'` (+ 개발만 `'unsafe-eval'`) | `'unsafe-inline'` 없음. 개발 모드의 React 는 서버 오류 스택을 `eval` 로 되살린다                                                                                                                                                                                         |
| `style-src`                 | `'self' 'unsafe-inline'`                                       | 서버가 그린 `style` 속성(next/image `fill` · 진행 막대 너비 · 증상 추이 높이 · 카카오 버튼 색)이 있다. nonce · 해시는 `style` 속성을 덮지 못하고, `style-src` 에 nonce 를 넣으면 `'unsafe-inline'` 이 무시돼 이 속성들이 막힌다(SRI 시험에서 `style-src-attr` 위반 확인) |
| `img-src` · `font-src`      | `'self'`                                                       | 이미지는 `public/` · 아이콘 라우트, 글꼴은 번들된 Pretendard 뿐이다. `data:` · `blob:` 을 쓰지 않는다                                                                                                                                                                    |
| `connect-src`               | `'self'` + 게이트웨이 오리진                                   | `NEXT_PUBLIC_API_BASE_URL`(`clientEnv.apiBaseUrl`)에서 오리진만 뽑는다. 주소가 아니거나 http(s) 가 아니면 `'self'` 만 둔다 — 빌드는 깨지지 않고 게이트웨이 요청만 막힌다(그런 값이면 API 계층도 요청을 만들 수 없다)                                                     |
| `manifest-src`              | `'self'`                                                       | `app/manifest.ts`                                                                                                                                                                                                                                                        |
| `worker-src`                | `'self'`                                                       | 2단계 서비스 워커(`public/sw.js`) 대비. 적지 않으면 `script-src` 로 떨어지는데, `'strict-dynamic'` 이 `'self'` 를 무시하고 nonce 는 워커에 붙지 않아 등록이 막힌다                                                                                                       |
| `object-src` · `base-uri`   | `'none'` · `'self'`                                            | 플러그인 · `<base>` 로 스크립트 주소를 바꾸는 길을 막는다                                                                                                                                                                                                                |
| `form-action`               | `'self'`                                                       | 폼은 화면 안에서 `fetch` 로 보낸다. 카카오 인가 이동은 `location.assign`(문서 이동)이라 CSP 대상이 아니다                                                                                                                                                                |
| `frame-ancestors`           | `'none'`                                                       | 다른 사이트가 화면을 iframe 에 넣지 못한다(`X-Frame-Options: DENY` 와 같은 뜻)                                                                                                                                                                                           |
| `upgrade-insecure-requests` | 사이트 주소가 https 인 운영 빌드만                             | `NEXT_PUBLIC_SITE_URL` 이 https(dev · 운영 웹)이고 개발 모드가 아닐 때. 로컬 http(`next start`)에서 켜면 브라우저에 따라 같은 오리진 http 요청까지 https 로 올려 청크를 받지 못할 수 있다                                                                                |

위반 보고(`report-to` · `report-uri`)는 아직 받지 않는다(후속). 받을 곳(게이트웨이 · 수집 서비스)과 보관 정책을 먼저 정한다 — 보고에는 주소(쿼리)가 실릴 수 있다.

**개발 모드 차이.** `pnpm dev` 는 `'unsafe-eval'` 을 더하고 `upgrade-insecure-requests` 를 빼며, HMR 웹소켓은 `connect-src 'self'` 로 받는다(Chrome 확인). 그 밖은 같다 — 개발 서버에서 위반이 없어도 프로덕션 빌드로 한 번 더 본다.

### 그 밖의 헤더

| 헤더                        | 값                                                                                 | 근거                                                                                                                                                                                                                                      |
| --------------------------- | ---------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `X-Content-Type-Options`    | `nosniff`                                                                          | 선언한 Content-Type 대로만 읽는다. 빌드 산출물(스크립트 · 스타일)에 의미가 커서 모든 경로에 붙인다                                                                                                                                        |
| `Referrer-Policy`           | `strict-origin-when-cross-origin`, 카카오 콜백만 `no-referrer`                     | 다른 오리진에는 오리진만 보낸다. 콜백은 주소의 인가 코드가 새지 않게(#167). `headers()` 는 같은 키를 **뒤 규칙이 덮어써** 콜백 규칙을 맨 뒤에 둔다                                                                                        |
| `Permissions-Policy`        | `geolocation=()` · `camera=()` · `microphone=()` · 결제 · USB · 블루투스 · 센서 등 | 위치를 쓰지 않는다는 도메인 규칙과 같다. 공유 창(`web-share`) · 링크 복사(`clipboard-write`)는 보고 공유 시트가 써 끄지 않는다. 알림 · 푸시는 이 헤더가 다루지 않는다. Chrome 이 모르는 이름을 넣으면 콘솔 경고가 나므로 아는 이름만 쓴다 |
| `X-Frame-Options`           | `DENY`                                                                             | `frame-ancestors` 를 모르는 브라우저와 CSP 가 붙지 않는 응답용                                                                                                                                                                            |
| `Strict-Transport-Security` | 앱에서 걸지 않는다                                                                 | TLS 를 끝내는 Infra nginx 가 웹 · API 도메인에 함께 건다(앱은 TLS 를 끝내는 곳이 아니고, API 도메인은 앱을 거치지 않으며, 두 곳에서 걸면 헤더가 겹친다). 브라우저는 http 응답의 HSTS 를 무시해 로컬에는 어느 쪽이든 영향이 없다           |

### 새 출처를 쓸 때

`src/lib/security/content-security-policy.ts` 의 `buildContentSecurityPolicy` 와 그 테스트, 위 표를 함께 고친다. 고친 뒤 프로덕션 빌드(`pnpm build` → `pnpm start`)를 브라우저로 열어 콘솔에 `Refused to …` · `violates the following Content Security Policy directive` 가 없는지 본다.

- 외부 이미지 · `data:` · `blob:`(예: 지도 타일, 캡처 미리보기) → `img-src`
- 백엔드 말고 다른 API(예: SGIS 를 브라우저에서 직접) → `connect-src`. 게이트웨이는 환경변수에서 자동으로 들어간다
- 외부 스크립트(예: 카카오 SDK) → `script-src` 에 출처를 더해도 `'strict-dynamic'` 이 무시한다. `next/script` 에 nonce 를 넘긴다 — proxy 에서 요청 헤더 `x-nonce` 를 더하고 서버 컴포넌트에서 `(await headers()).get('x-nonce')` 로 읽는다(가이드 "Reading the nonce"). 지금은 쓰는 곳이 없어 싣지 않는다. 그 스크립트가 부르는 요청 · 이미지 · iframe 출처도 따로 더한다
- iframe(예: 외부 공유 · 지도 위젯) → `frame-src`(지금은 `default-src 'self'` 로 막힌다)
- 다른 오리진으로 폼을 바로 보냄 → `form-action`. 외부 글꼴 · 스타일시트 → `font-src` · `style-src`
- 새 브라우저 기능(예: 2단계 카메라) → `security-headers.ts` 의 `PERMISSIONS_POLICY` 에서 그 이름을 뺀다

**한계.** proxy matcher 밖 주소에서 HTML 문서(없는 화면 404)가 나오면 CSP 가 붙지 않는다 — 점이 든 경로(`/x.txt`), `api/` 로 시작하는 경로, `icon` · `apple-icon` 으로 시작하는 경로(접두어로 맞춰 `/iconx` 도 빠진다), `app-icons/` 아래다. nonce 없이 고정 CSP 를 붙이면 그 화면의 스크립트가 막힌다. 보안 헤더는 붙는다. Next 의 SRI 는 실험 기능이라 켜지 않았다.

### 카카오 콜백 — 쿼리를 fragment 로 옮겨 303

동적 렌더링이면 Next 가 요청 주소를 응답 HTML 의 RSC 페이로드(첫 주소 `"c"` · `"q"` · 페이지 세그먼트 키 `__PAGE__?{"code":…}`)에 싣는다. #167 은 콜백이 정적 페이지라 본문에 인가 코드가 없었는데, 모든 화면이 동적이 되면서 `?code=…&state=…` 를 단 채 그리면 `code` · `state` 가 문서 HTML 에 남는다. 그래서 proxy 가 GET 콜백(`KAKAO_CALLBACK_PATH`)에 쿼리가 있으면 쿼리를 fragment 로 옮겨(Next 가 Location 을 다시 인코딩할 수 있어 바이트는 달라질 수 있으나 URLSearchParams 로 읽는 키 · 값은 같다) 같은 경로로 **303** 리다이렉트한다(`features/auth/kakao-callback-redirect.ts`, `#code=…&state=…` · `#error=…`). 백엔드 `KAKAO_REDIRECT_URI` 는 그대로다.

- **rewrite 로는 안 된다.** 쿼리를 뗀 경로로 rewrite 해도 `"q"` · 세그먼트 키만 빠지고 첫 주소 `"c"` 에는 남는다 — app-render 가 첫 주소를 rewrite 와 무관하게 원래 요청 주소(`req.url`)로 만든다(`node_modules/next/dist/server/app-render/app-render.js` 의 `parseRelativeUrl(req.url)` → `prepareInitialCanonicalUrl`). fragment 는 서버로 가지 않아 다시 받은 문서는 쿼리 없이 그려진다.
- 303 응답: `Cache-Control: no-store` · `Referrer-Policy: no-referrer` 와 CSP · 보안 헤더. 303 이라 `?code=` 주소는 방문 기록에 남지 않고(최종 주소만 남음), 화면이 hash 를 지운 뒤 다음 화면으로 기록을 바꿔 간다.
- 리다이렉트 응답 본문에는 Next 가 Location 값을 그대로 쓴다(`node_modules/next/dist/server/lib/router-server.js` 의 `res.end(destination)` — proxy 리다이렉트는 본문을 줄 수 없다). Location 헤더와 같은 값이고 브라우저는 그리지 않는다.
- 첫 진입보다 먼저 본다. 콜백은 원래 시작 화면으로 보내지 않는 화면이고, 방문 표시는 리다이렉트를 받은 문서 요청에서 심는다.
- 화면(`kakao-callback-screen.tsx`)은 hash 를 먼저, 비었으면 쿼리를 읽는다(proxy 를 거치지 않은 경우). 주소 지우기 · 한 번만 보내기 · 복원 대기는 그대로다.
- **서버에서 콜백 값을 읽는 코드를 두지 않는다**(페이지 `searchParams` · `generateMetadata` 등) — 서버는 값을 받지 않는다.
- **회귀 확인**(콜백 · proxy · 렌더링 방식을 바꿀 때): `pnpm build` → `pnpm start` 뒤
  - `curl -s -i 'http://localhost:3000/login/kakao/callback?code=SECRETCODE123&state=STATEXYZ'` → `303` · `location: /login/kakao/callback#code=SECRETCODE123&state=STATEXYZ`
  - `curl -s -H 'Cookie: sc_visited=1' 'http://localhost:3000/login/kakao/callback' | grep -c SECRETCODE123` → `0`
  - 브라우저로 `?code=link&state=x`(목데이터)를 열어 계정 연결 확인(`/login/kakao/link`)으로 가고 주소에 `#` 가 남지 않는지 본다.

## 뒤로 가기 캐시(bfcache)

뒤로 · 앞으로 가기에서 화면을 다시 받지 않고 메모리에서 바로 되살리는 브라우저 캐시다(#186). **개인정보(건강 · 증상)와 맞바꾸지 않는 범위에서만 연다.** 서버가 그리는 HTML · RSC 는 회원별이 아니다 — 세션 · 내 정보 · 이번 주 보고는 서버가 모르고 클라이언트 메모리(세션 · 회원 정보 · 이번 주 보고 저장소)에만 있다. 위험은 메모리째 되살아난 회원 화면이다.

| 응답                                            | `Cache-Control`                                                            | 어디서                                                             |
| ----------------------------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 화면 문서                                       | Next 기본값 `private, no-cache, no-store, max-age=0, must-revalidate` 유지 | Next(동적 렌더링)                                                  |
| 앱 안 이동 · 미리 받기(RSC, 요청 헤더 `rsc: 1`) | **`private, no-cache`** (`max-age` 없음 — 쓸 때마다 다시 확인)             | `next.config.ts` `headers()` + `src/lib/security/cache-headers.ts` |
| 카카오 콜백(`/login/kakao/callback`) 문서 · RSC | `no-store`(문서 기본값과 같은 값)                                          | `next.config.ts` 콜백 규칙(맨 뒤)                                  |
| proxy 리다이렉트(첫 진입 307 · 콜백 303)        | `private, no-store` · `no-store` 그대로                                    | proxy — `headers()` 가 덮지 않는다(실측)                           |

- **막던 것은 RSC 응답이다.** 프로덕션 빌드를 헤드리스 Chrome 154 로 열어 CDP `Page.backForwardCacheNotUsed` 를 보면(2026-10-03) `/` · `/login` · `/map` · `/official` 은 `JsNetworkRequestReceivedCacheControlNoStoreResource` 로 막혔다 — 화면의 `Link` 가 미리 받은 RSC(`?_rsc=`) 응답이 `no-store` 였다. `MainResourceHasCacheControlNoStore` 는 같이 찍히지만 혼자서는 막지 않는다(RSC 요청이 없는 `/start` · `/install` 은 문서가 `no-store` 여도 들어갔다). RSC 응답만 `private, no-cache` 로 바꾸자 7개 주소(목 회원 홈 포함)가 모두 되살아났고 콘솔 오류 · CSP 위반은 0 이었다.
- **문서를 `no-store` 로 두는 이유.** Chrome 은 `no-store` 문서도 bfcache 에 넣되, 문서를 받은 뒤 그 주소에 실리는 쿠키가 바뀌었으면(화면 자신이 바꾼 것 포함) 되살리지 않는다(`CacheControlNoStoreCookieModified`). 로그아웃 · 만료 · 재로그인 응답은 세션 힌트 쿠키(`sc_session`)를 지우므로, 같은 기기의 다른 탭에서 로그아웃하면 앞 회원 화면이 되살아나지 않는다. 실데이터 회원 화면은 세션을 정할 때마다 힌트 쿠키를 다시 써서(14일 연장) Chrome 에서는 bfcache 에 들지 않는다 — 개인정보 쪽으로 기운 결과라 그대로 둔다. 문서를 `no-cache` 로 바꾸면 이 보호가 사라지고, 기록 이동이 디스크 캐시의 문서를 재검증 없이 쓴다(아래).
- **nonce CSP 와 HTTP 캐시.** `max-age` 를 주지 않는다. 시험 삼아 문서까지 `private, no-cache` 로 빌드하고 bfcache 를 끈 Chrome 으로 뒤로 가 보면 문서를 디스크 캐시에서 재검증 없이 꺼내고, 캐시된 CSP 헤더의 nonce 와 문서 스크립트의 nonce 가 같아 스크립트 · 하이드레이션이 정상이었다(위반 0). 지금 정책은 문서를 저장하지 않으니 nonce 는 늘 새 응답 것이다. RSC 페이로드에도 그 응답의 nonce 가 실리지만 문서 CSP 와 무관하고(앱 안 이동이 넣는 청크는 `'strict-dynamic'` 으로 돈다), 검증자(ETag)가 없어 HTTP 캐시에서 재사용되지 않는다.
- **복원 시 세션 재확인**(위 "세션 저장소"): Safari 등 다른 브라우저 · 쿠키가 바뀌지 않은 경우(다른 기기 로그아웃 · refresh 만료 · 탭 알림을 놓침)를 위한 두 번째 장치다. `pageshow` 의 `persisted` 에서 회원이었으면 바로 `restoring` 으로 가려 회원 UI(`useAuth` 는 `guest`) · 회원 정보 · 이번 주 보고(저장소가 지운다)가 회원으로 그리지 않고 가드는 판단을 미룬다. 재발급에 성공하면 그 응답의 회원으로 다시 읽고, 재로그인 · 탈퇴 · 정지면 조용히 비회원(힌트 삭제), 일시 장애면 확인하지 못했으니 비회원으로 보인다(힌트는 남김). 문서도 `no-cache` 로 둔 시험 빌드에서 회원 화면이 되살아날 때 `pageshow` 직후 첫 프레임에 회원 표시가 없었고, 서버에서 세션을 끝냈으면 비회원, 다른 회원으로 바뀌었으면 그 회원으로 정해졌다. 목데이터 모드는 그대로다(목 세션을 건드리지 않는다).
- **민감 입력은 얼기 전에 비운다.** 비회원 화면이 bfcache 에 들면서 입력만 하고 보내지 않은 값도 함께 되살아난다(공용 기기의 다음 사람이 `비밀번호 보기`로 평문을 보거나 그대로 보낼 수 있다). 그래서 `src/lib/use-clear-on-page-freeze.ts` 의 `useClearOnPageFreeze(clear)` 가 `pagehide` 의 `persisted`(얼기 직전)에 `clear` 를 `flushSync` 로 그 자리에서 커밋해 얼기 전 DOM 에 값을 남기지 않고, `pageshow` 의 `persisted` 에서 한 번 더 부른다.
  - 거는 곳: 이메일 로그인(이메일 · 비밀번호), 가입 · 재설정의 이메일 단계(`email-step.tsx`) · 코드 단계(`code-step.tsx`), 가입 계정(비밀번호 · 확인 · 닉네임), 새 비밀번호(재설정), 내 비밀번호 변경(현재 · 새 · 확인), `TextField` 의 비밀번호 보기(끈다)
  - 첫 진입 Provider(`onboarding-context.tsx`)는 메모리의 가입 초안(비밀번호 · 이메일 · 닉네임 · 가입 종류) · 재설정 초안(재설정 토큰) · 카카오 연결 확인의 가린 이메일을 비운다. 성인 확인 · 알림 선택도 사람마다 받으므로 비운다. 가입 마무리 진행(`membership`) · 동네(개인을 알아보는 값이 아니다)는 둔다
  - 비우면 단계 화면은 값이 없을 때처럼 흐름의 처음으로 간다 — 가입 코드 · 계정 → 가입 이메일, 새 비밀번호 → 재설정 이메일, 카카오 연결 확인 → 로그인 방법 선택(다음 사람이 `연결하고 계속하기` 를 누를 수 없다). 얼기 직전에 보낸 이동은 브라우저가 버려 빈 화면으로 되살아났으므로(Chrome 실측), Provider 가 되살아날 때(`pageshow` 의 `persisted`) 화면을 다시 붙여 단계 확인을 다시 돌린다
  - 실측(프로덕션 빌드 · 헤드리스 Chrome, 목데이터): 위 8개 흐름에서 입력 → 다른 사이트 → 뒤로 하면 모두 `persisted` 로 되살아나고, `pageshow` 첫 처리 시점(얼어 있던 DOM)에 이미 칸이 비어 있었으며, 콘솔 오류 · CSP 위반은 0 이었다. `/` · `/login` · `/map` 의 bfcache 는 그대로다
  - **새 인증 폼(비밀번호 · 인증 코드 · 이메일 칸)을 만들거나 그런 값을 메모리(Provider · 모듈)에 들면 이 훅을 건다.** 동네 검색어 · 공유 링크처럼 개인을 알아보지 않는 칸은 걸지 않는다
- **탭 알림.** 얼어 있는 화면이 `BroadcastChannel`(`sneezecast:session`) 메시지를 받으면 Chrome 은 캐시에서 버린다(`BroadcastChannelOnMessage`). 내 동네 저장 알림(`region-changed`, #190)도 같다 — 다른 탭에서 동네를 바꾸면 얼어 있던 화면은 되살아나지 않고 새로 연다(옛 동네를 든 채 되살아나지 않는다). 통로를 열어 두는 것만으로는 막지 않는다. 재발급 잠금(`navigator.locks`)은 재발급하는 동안만 잡는다.
- **회귀 확인**(캐시 헤더 · proxy · 세션 복원을 바꿀 때): `pnpm build` → `next start` 뒤
  - `curl -s -o /dev/null -D - -H 'Cookie: sc_visited=1' -H 'RSC: 1' 'http://localhost:3000/map?_rsc'` → `Cache-Control: private, no-cache`, 같은 요청을 `/login/kakao/callback?_rsc` 로 → `no-store`. 문서(`RSC` 헤더 없이)는 모두 Next 기본값.
  - Lighthouse `bf-cache` 감사(`pnpm perf:lighthouse`)가 7개 주소 모두 통과(performance.md "bfcache (#186)").
- **한계.** 되살아난 화면은 `pageshow` 처리 전까지 얼기 전 DOM(회원 표시)을 메모리에 들고 있다. 첫 프레임 전에 가리는 것은 확인했지만, 브라우저가 그 전에 옛 화면을 잠깐 보이는지는 구현에 달렸다. Safari · Firefox 는 실측하지 않았다.

## 테스트

- 파일은 대상 옆에 `*.test.ts(x)` 로 둔다. 도구 스크립트(`scripts/*.mjs`, 빌드 · 타입체크 밖)는 옆에 `*.test.mjs` 로 둔다.
- 기본 환경은 node 다. DOM 이 필요한 컴포넌트 테스트는 파일 맨 위에 `// @vitest-environment jsdom` 을 적어 그 파일만 켠다.
- 컴포넌트 테스트는 Testing Library(`@testing-library/react` · `user-event`)로 역할·이름으로 찾는다(`getByRole`). 렌더 정리는 `src/test/setup.ts` 가 DOM 환경일 때만 등록한다.
- 테스트에는 Next 라우터가 없다. `useSearchParams` 를 쓰는 화면은 `vi.mock('next/navigation', …)` 으로 쿼리를 흉내 낸다 (`features/home/home-screen.test.tsx`).
- 스타일은 클래스 이름(`classList`)으로 확인한다. 실제 픽셀 값은 `/dev/components` 를 브라우저로 열어 확인한다.
- jsdom 은 `<dialog>` 의 `showModal()` · `close()` 가 없어 `src/test/setup.ts` 가 `open` 속성만 흉내 낸다. 포커스 가두기 · Esc 같은 실제 동작은 브라우저로 확인한다.
- jsdom 은 27 을 쓴다. 30 은 Node 22.22.2 이상을 요구해 `engines`(22.13 이상)와 맞지 않는다.

## 성능 측정

측정 방법 · 예산 · 기준선 · 후속 후보의 정본은 [performance.md](performance.md) 다.

- 프로덕션 빌드를 잰다(`pnpm build` → `pnpm perf:lighthouse`, `next start -p 3100`). 개발 서버는 재지 않는다.
- Lighthouse 기본 설정(모바일 · 시뮬레이션 쓰로틀링)으로 URL 마다 3회 재고 중앙값을 본다. 대상 URL · 예산은 `lighthouserc.yml` 에 있다.
- 예산은 모두 경고(`warn`)다. `frontend-ci` 에서도 경고만 내고 PR 을 막지 않는다. 결과를 외부에 올리지 않는다.
- 화면 · 의존성을 크게 바꾼 PR 은 CI 요약의 예산 경고를 보고, 의도한 변화면 performance.md 의 기준선 · 예산을 함께 고친다.

## 완료 전 확인

```bash
pnpm qa:verify   # format:check → lint → typecheck → test → build
```

통과하지 않으면 완료로 보고하지 않는다. 실패나 미실행은 PR 의 "검증 내역" 에 그대로 적는다.
