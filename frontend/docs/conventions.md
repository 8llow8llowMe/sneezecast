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
├── proxy.ts             # Next 16 Proxy(옛 미들웨어). 처음 온 사람을 시작 화면으로 보낸다 — 로직은 features/onboarding/first-visit.ts
├── src/
│   ├── components/      # 도메인을 모르는 공통 UI (버튼, 리스트 행, 바텀시트 …)
│   ├── features/<도메인>/ # 화면별 UI (home, report, onboarding, region, map, notice, official, admin …)
│   ├── lib/             # 로직. api/ 는 API 호출 계층, session/ 은 세션 저장소, env.client.ts 는 공개 환경변수
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
- **로딩 경계(`loading.tsx`)는 루트에 두지 않고 주요 메뉴 화면에만 둔다** (`app/(home)/loading.tsx` · `app/map/loading.tsx` · `app/me/loading.tsx`). 루트에 두면 모든 경로가 Suspense 안에서 스트리밍되어 `notFound()` 가 404 대신 200 으로 나간다. 홈을 괄호 폴더 `(home)` 에 둔 것도 경계를 홈에만 걸기 위해서다(주소는 `/`). 지도 · 내 정보는 경로 폴더(`app/map` · `app/me`)라 그 경로에만 걸려 괄호 폴더가 필요 없다.
- **브라우저 연결 상태는 `src/lib/use-online.ts` 의 `useOnline()` 으로만 읽는다.** 서버 그림과 하이드레이션 첫 그림은 늘 온라인이다(SSR 불일치 방지). 오프라인 안내는 화면 전체를 바꾸지 않고 띠(`OfflineNotice`)로 보인다.
- **로그인 만료는 `src/lib/session-expiry.ts` 의 `notifySessionExpired()` 하나로 알린다.** 루트 레이아웃의 `features/auth/session-expiry-watcher.tsx` 가 (목데이터 모드면 목 세션을 비우고) `/login?reason=expired` 로 `replace` 한다. 실데이터 모드는 세션 저장소가 재발급에 실패했을 때 세션을 먼저 비우고 부른다(아래 "세션 저장소"). 화면 코드는 401 을 따로 다루지 않는다.
- **회원만 보는 화면의 가드는 회원 상태가 정해진 뒤 판단한다.** 서버와 하이드레이션 첫 그림의 회원 상태는 늘 `guest` 이고(`useAuth`), 실데이터 모드는 새로고침 뒤 세션을 되살리는(재발급) 동안에도 `guest` 다. 그 값으로 로그인에 보내면 회원도 튕긴다. `features/auth/use-auth.ts` 의 `useAuthSettled()`(하이드레이션을 마쳤고, 목데이터이거나 실데이터 세션이 `member` · `guest` 로 정해짐)가 true 인 그림의 상태로만 판단하고 Next 라우터(`router.replace`)로 보낸다 (`features/me/member-gate.ts`, 약관 재동의 · 동네 다시 고르기 화면도 같다).
  - 반대 방향(회원이 연 시작 · 로그인 · 가입 화면 → 홈 또는 `?next=`)은 첫 진입 레이아웃의 `features/auth/guest-only-gate.tsx` 다. 같은 이유로 `useAuthSettled()` 가 true 일 때 판단하고, **화면에 닿을 때의 상태로 한 번만** 판단한다 — 그 화면에서 회원이 되는 것(로그인 성공)은 화면이 스스로 이동하므로 두 이동이 겹치지 않게 끼어들지 않는다(docs/design/SCREENS.md "첫 진입").
- **router 내비게이션이 대기 중일 때 `history.replaceState` · `pushState` 를 부르면 Next 가 그 내비게이션을 버린다 — 가드가 보낼 곳이 있으면 주소 정리를 하지 않는다.** 원시 history 변경이 Next 의 복원(ACTION_RESTORE)을 일으켜 대기 중인 `router.replace` 가 버려진다(프로덕션 빌드에서 재현). 같은 그림에서 주소 쿼리를 정리하는 화면(홈의 `?report=` · 내 정보의 `?confirm=` 정리)은 `useRequiredStepsGate` · `useRequiredStepsTarget`(`features/me/member-gate.ts`)이 돌려준 보낼 곳이 있으면 정리를 건너뛴다.
- **레이아웃 · 정적 라우트에서 `useSearchParams` 를 읽는 클라이언트 컴포넌트는 `<Suspense>` 로 감싼다.** 감싸지 않으면 `next build` 의 정적 생성이 `missing-suspense-with-csr-bailout` 으로 멈춘다(dev 서버에서는 드러나지 않는다). 하이드레이션 뒤에야 그리는 화면은 대체 그림을 비워 둔다(`app/me/layout.tsx` 의 가드, `app/(onboarding)/terms/reconsent/page.tsx`). 페이지가 `searchParams` 를 await 하면 동적 라우트라 필요 없다.
- **돌아갈 곳(`?next=`)은 허용 목록 안의 경로만 받는다.** 가드가 다른 화면으로 보냈다가 돌려보낼 때 `?next=` 를 쓰고, 받는 쪽은 `features/auth/required-steps.ts` 의 `safeNextPath` 로 정확히 같은 경로(`NEXT_PATHS`: `/` · `/me` · `/me/devices` · `/me/password` · `/me/region`)일 때만 따른다. 머리줄 동네 이름이 여는 둘러볼 동네 고르기(`/browse/region?next=`)는 따로 둔 허용 목록(`features/onboarding/browse-return.ts` 의 `BROWSE_NEXT_PATHS`, 머리줄에 동네 이름이 있는 화면)을 같은 방식으로 받는다(#141). 로그인 화면(`/login` → `/login/email`)도 `features/auth/login-return.ts` 로 `?next=` 를 받아 로그인 뒤 그곳으로 간다(내 정보 가드가 씀). 보고하려던 로그인은 `?intent=report` 를 더 받아 로그인 뒤 같은 동네 홈의 보고 진입(`/?region=…&report=start`)으로 간다 — 받는 값은 `report` 하나, 돌아갈 곳이 홈일 때만 받고(그 밖이면 버림) `NEXT_PATHS` 규칙은 그대로이며, 회원 상태에 맞는 시트는 홈의 `guardReportEntry` 가 고친다(#136). 회원이 그 로그인 화면에 닿으면(사실상 로그인 성공 뒤 브라우저 뒤로) 첫 진입 가드는 보고 진입을 붙이지 않고 홈으로만 보낸다 — 뒤로 가려는 사람을 붙잡지 않는다. 로그인으로 넘기는 쿼리는 `next` 와 둘러보기 동네뿐이고 목 덮어쓰기는 넘기지 않는다(로그인이 세션을 바꾸므로). 쿼리 · `#` 가 붙었거나 다른 오리진(`//…` · `https://…`)이면 홈으로 보낸다(오픈 리다이렉트 방지). 함께 넘길 쿼리는 둘러보기 동네(`region`)와 QA 용 목 덮어쓰기(`mock-auth` · `mock-provider` · `mock-required`, 목데이터 모드에서만 듣는다 — 아래 "데이터 출처")뿐이다(`carriedParams`). 새 화면을 가드에 걸면 목록에 더한다.
- **화면 위에 뜨는 시트 · 대화상자의 열림 상태는 주소 쿼리에 둔다** (`docs/design/SCREENS.md` 의 제안 라우트, 예: 판단 기준 `/?explain=1`). 새로고침 · 공유해도 같은 화면이 열린다. `src/lib/use-modal-param.ts` 를 쓴다. 단계마다 `push` 로 기록을 쌓아 휴대폰 뒤로 가기가 이전 단계 · 닫기로 이어지게 하고, 보낸 뒤 완료처럼 되돌아오면 안 되는 단계는 `replace` 로 바꾼다(`remove` 로 다른 쿼리를 같은 기록 항목에서 함께 지울 수 있다). `close` 는 이 훅이 쌓은 깊이만큼만 되돌린다 — 깊이는 `history.state` 에 두어 뒤로 가기로 단계를 되돌린 뒤 닫아도 홈 앞까지만 간다. 주소로 바로 들어와 쌓은 기록이 없으면 쿼리만 지운다. `router.push` 는 서버에 화면을 다시 요청하므로 쓰지 않는다.
- **같은 문서 안 `#` 링크(`<a href="#id">`)를 쓰지 않는다.** Next 가 모르는 기록 항목(`history.state` 가 null)이 생겨, 그 뒤 연 시트 · 대화상자의 닫기(`history.go(-1)`)가 그 항목으로 돌아가고 Next 가 무시해 첫 닫기에 닫히지 않는다. 섹션 바로가기는 버튼으로 `scrollIntoView` 한 뒤 제목(`tabIndex={-1}`)에 포커스를 준다(`features/me/me-screen.tsx`).
- **마운트 effect 에서 history 를 바꾸지 않는다.** 하이드레이션 첫 커밋에서는 Next 가 아직 history 를 감싸지 않는다(최상위 라우터 effect 보다 자식 effect 가 먼저 돈다) — 그때 바꾼 주소는 `useSearchParams` 가 모른다. 미뤄야 하면 `setTimeout(0)` 으로 미루고 cleanup 에서 취소한다(`features/home/home-screen.tsx` 의 보고 진입 정리). 열림처럼 그 값으로 그리는 것은 바뀔 값으로 미리 계산해 첫 그림부터 맞춘다.
- 서버에 보내는 동작(보고 보내기 · 고치기 · 되돌리기)은 연동 전에도 `features/<도메인>/*-client.ts` 에 Promise 를 돌려주는 함수로 둔다. 연동 때 함수 안을 `src/lib/api/` 호출로 바꾸고 화면은 출처 인자 · 결과 갈래만 더한다 (`features/report/report-client.ts`, #165 에서 연동).
- API 연동 전 화면은 `features/<도메인>/mock.ts` 의 목 데이터로 만든다. 목 데이터의 기본값은 `자료 부족` 처럼 수치를 지어내지 않는 상태로 둔다. 연동 이슈에서는 목 데이터를 지우지 않고 데이터 출처 장치(아래 "API 계층" 의 "데이터 출처")의 목 갈래로 남긴다.

- 화면 코드(`app/`, `src/features/`, `src/components/`)에서 `fetch` 를 직접 부르지 않는다. API 호출은 `src/lib/api/` 로 모은다 (ESLint 로 막는다, 아래 "API 계층").
- 공개 환경변수는 `src/lib/env.client.ts` 에서만 읽는다. `process.env.NEXT_PUBLIC_X` 는 리터럴로 읽어야 빌드 때 치환된다.
- 목록은 `.env.example` 에 있다. 로컬 개발도 dev 게이트웨이(`https://api-dev.sneezecast.com`)를 쓴다.
- `localStorage` · `sessionStorage` 는 ESLint 가 막는다. 건강·증상 정보는 민감정보라 브라우저에 아무렇게나 남기지 않는다. 기기 토큰처럼 꼭 필요한 값은 저장 모듈 하나로 모으고 그 줄에 근거 주석을 남긴다.

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

| 모듈               | 하는 일                                                                                                                               |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| `session-store.ts` | 스냅숏(`idle` · `restoring` · `guest` · `member`+요약) · `setSession` · `clearSession` · `restoreSession` · `startSession`(슬롯 설치) |
| `session-hint.ts`  | 힌트 쿠키 `sc_session` 읽기 · 쓰기                                                                                                    |
| `session-sync.ts`  | 탭 사이 알림(`BroadcastChannel` `sneezecast:session`) · 재발급 잠금(`navigator.locks` `sneezecast:session-reissue`). 없으면 강등      |
| `use-session.ts`   | `useSession()` — 서버 · 하이드레이션 첫 그림은 늘 `idle`                                                                              |

- **토큰은 `session-store.ts` 의 지역 변수에만 둔다.** 화면이 읽는 스냅숏에는 회원 요약(아이디 · 역할 · 재동의 항목 · 보고 가능)만 있다. 쿠키 · 브라우저 저장소 · 주소 · `console` 에 남기지 않고, 같은 오리진의 다른 탭에만 알림으로 넘긴다.
- 만료 시각은 `accessTokenExpiresIn`(초)으로 정한다(JWT 를 풀지 않음). 요청 직전에 만료 30초 전(`ACCESS_EXPIRY_MARGIN_MS`)이면 먼저 재발급한다 — 타이머는 없다. 재발급은 `POST /api/v1/auth/token/reissue` 를 `auth: false` 로 부른다.
- 재발급은 탭 안에서 하나로 묶고(동시 401 여러 건 → 재발급 1회), 탭 사이는 잠금으로 줄 세운다. 잠금을 잡았을 때 그사이 다른 탭 알림으로 세션이 바뀌었으면 재발급하지 않고 그 토큰을 쓴다. 응답을 기다리는 동안 로그아웃했으면 늦은 응답을 버린다.
- 재발급 결과: 경합(`reissue-conflict`, `AUTH_016`)이면 `REISSUE_CONFLICT_RETRY_DELAY_MS`(300ms — 이긴 탭의 새 refresh 쿠키가 반영될 틈)를 기다려 한 번 다시 한다. 재로그인(`relogin`, `AUTH_014/015`) · 탈퇴 · 정지(`MEMBER_002/003`)면 세션을 비우고 다른 탭에 알리며 `notifySessionExpired()`(`src/lib/session-expiry.ts`)를 부른다. 두 번째 경합은 **이 탭만** 비우고 다른 탭에 알리지 않는다(이긴 탭의 세션은 멀쩡할 수 있다 — 잠금이 없는 환경).
- 재발급이 일시 장애(`unavailable`)이거나 분류에 없는 오류면 세션을 끝내지 않는다 — 회원 요약은 두고 토큰만 버린다. 세션 중이면 공급자 · 갈아 끼우기가 일시 장애(`UNAVAILABLE`)를 던져 요청이 "잠시 뒤 다시" 로 끝난다(토큰 없이 보내 `SECURITY_001` 로 보이지 않게). 다음 요청이 다시 재발급한다.
- **새로고침 복원**: refresh 쿠키는 게이트웨이 호스트 · `Path=/api/v1/auth` 전용 HttpOnly 라 화면이 있는지 모른다. 로그인 · 재발급에 성공하면 1st-party 힌트 쿠키 **`sc_session=1`**(`Path=/` · `SameSite=Lax` · HTTPS 면 `Secure` · 14일, 값은 고정 `1`)을 남기고, 로그아웃 · 만료 · 재로그인 응답이면 지운다. 앱을 열 때 실데이터 모드이고 힌트가 있을 때만 재발급을 한 번 한다(`restoring` → `member`). `SessionBootstrap` 은 하이드레이션 커밋의 effect 에서 출처 쿠키를 직접 읽어(`readBrowserDataSource`) 바로 시작한다 — 훅 값(첫 그림은 서버 기본값)을 기다리면 `idle` 동안 나간 회원 요청이 토큰 없이 나간다. 힌트가 없으면 요청 없이 `guest` 다. 복원이 재로그인 · 탈퇴 · 정지 · 두 번째 경합으로 끝나면 알리지 않고(방송도 없음) 힌트를 지우며, 일시 장애면 힌트를 남긴 채 `guest` 로 보인다. 복원 중 요청은 복원을 기다려 그 토큰을 싣는다.
- 만료 알림(`notifySessionExpired`)은 이 탭에 세션이 있었을 때만 부른다. 다른 탭의 로그아웃 · 만료 알림을 받으면 조용히 `guest` 가 된다.
- 화면은 `features/auth/use-auth.ts` 의 `useAuth()`(회원 상태) · `useAuthSettled()`(정해졌는지)로 읽는다. `useMockAuth` 는 같은 훅의 옛 이름이다(이름 정리는 후속).
- **실데이터 모드에서 회원이 되는 길은 이메일 로그인(#163)이다.** `loginWithEmail(…, 'api')` 이 응답을 `setSession` 에 넣고, `logout(…, 'api')` 이 `clearSession('logout')` 을 부른다(실패 정책은 [api-contract-draft.md](api-contract-draft.md) "인증" 의 "프론트 연동 (#163)"). 비밀번호 재설정 · 변경과 로그인한 기기는 #166 에서 연동했다 — 재설정 성공(재설정한 이메일이 이 탭 계정의 이메일과 같을 때 — 다르면 그대로, 모르면 `logout('api')`) · 이 기기 세션 로그아웃(`revokeSession` 의 `current`)은 서버가 이 기기 세션도 끝내므로 `clearSession('logout')`, 다른 기기 모두 로그아웃이 `AUTH_014`(이 기기 세션을 서버가 모름)면 `clearSession('expired')` 이고, 비밀번호 변경은 이 기기 세션을 그대로 둔다(계약 · 오류 매핑은 [api-contract-draft.md](api-contract-draft.md) "인증" · "회원" 의 "프론트 연동 (#166)"). 카카오 로그인 · 카카오 가입 · 동의(건강정보 · 재동의) · 탈퇴는 아직 출처와 무관하게 목이다. 로그아웃 중 재발급이 재로그인으로 끝나 세션이 이미 비었으면 다시 비우지 않고, 성공 · 세션 사라짐 모두 만료 진행 표시(`clearSessionExpiring`)를 끈다.
- **회원 정보 저장소**(`features/auth/member-info.ts`, #164): 세션이 회원이 되면(`memberId` 가 바뀔 때만) 내 정보(`GET /api/v1/members/me`) · 내 동네(`GET /api/v1/members/me/region`)를 한 번씩 읽어 메모리에 두고, 비회원이 되면 바로 지운다(늦은 응답은 버린다). 두 요청은 따로 `loading` · `ready` · `failed` 이고 `retryMemberInfo()` 는 실패한 쪽만 다시 읽는다. 서버 응답이 읽은 값과 어긋났을 때는 지금 값을 둔 채 다시 읽는다(`reloadMemberRegion()` #165 · `reloadMemberInfo()` #166 — 비밀번호 변경이 `MEMBER_007`). `SessionBootstrap` 이 `startMemberInfo()` 로 세션에 잇는다. 화면은 출처를 가리는 훅(`useMockProfile` · `useMockProfileStatus` · `useMemberRegion` · `useMemberRegionStatus` · `useMemberRequirements`)으로만 읽는다 — 실데이터에서 읽기 전 · 실패면 프로필 · 내 동네가 null 이고 **예시 값으로 채우지 않는다.** 내 정보가 `MEMBER_004` 면 `clearSession('withdrawn')`, 탈퇴 · 정지(`MEMBER_002` · `003`)면 재발급과 같은 `clearSession('expired')` 다. 실데이터 `saveRegion` 은 세션이 회원이 아니면 요청 없이 거부한다. 내 동네 저장(`saveRegion(…, 'api')`)은 응답을 저장소에 바로 넣는다(계약 · 오류 매핑은 [api-contract-draft.md](api-contract-draft.md) "회원").
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
- **루트 레이아웃에서 `cookies()` 를 부르지 않는다** — 모든 라우트가 동적 렌더링이 된다. 쿠키는 이미 동적인(`searchParams` 를 await 하는) 페이지에서만 읽는다. 그래서 루트 레이아웃의 토글과 `useDataSource()` 는 서버 그림 · 하이드레이션 첫 그림에서 기본값이고 그 뒤 쿠키 값을 읽는다(토글은 하이드레이션 뒤에만 그린다). 출처로 요청하는 effect 는 출처를 의존값에 넣는다.
- 토글은 쿠키를 쓰고 `router.refresh()` 로 서버 컴포넌트를 다시 그린다. 클라이언트 화면은 `useDataSource()` 로 따라간다.
- 토글은 body 마지막 자식이다(맨 앞이면 Tab 첫 포커스가 된다). z-index 를 주지 않는다 — z-index 가 있는 시트 · 버튼 묶음 · 가림막(z-10 · z-20)은 DOM 순서와 무관하게 토글 위에 온다. 모바일은 탭바 · 화면 아래 버튼 묶음보다 위(`bottom-dev-toggle`)에 띄운다.
- 백엔드에 아직 없는 API(BE 미정)는 출처와 무관하게 목이다(예: `listSuccessorDistricts`, 동네 안내 내용). 함수 주석에 적는다.
- **회원 상태도 출처를 따른다.** 실데이터는 세션 저장소(위), 목데이터는 목 세션(`features/auth/auth-client.ts`)이다. 둘은 따로라 토글은 어느 쪽도 지우지 않고, 토글로 실데이터가 되면 `SessionBootstrap` 이 세션을 되살린다(여러 번 불러도 한 번).
- **QA 덮어쓰기(`?mock-auth=` · `?mock-provider=` · `?mock-required=` · `?mock-session=`)는 목데이터 모드에서만 듣는다.** 실데이터에서 들으면 주소만으로 회원 · 동의 확인을 건너뛴다. 운영은 늘 실데이터라 덮어쓰기가 듣지 않는다. 홈 자료 덮어쓰기(`?mock=`) · 알림 덮어쓰기(`?mock-push=`)는 회원 확인과 무관해 그대로다.

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
