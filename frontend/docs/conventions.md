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
- 여러 화면이 같이 쓰는 데이터는 화면이 아니라 데이터 도메인에 둔다. 행정동(`features/region`)은 홈 · 첫 진입이 같이 쓴다. 도메인끼리 서로 모르게 해야 하면 `app/` 에서 맞춘다 (홈 `?region=` 은 `app/page.tsx` 가 `findDistrict` 로 이름을 덮어쓴다).

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
| 모서리          | `rounded-card` · `rounded-button` · `rounded-chip` · `rounded-sheet` · `rounded-dialog` · `rounded-small` · `rounded-bar`(2) · `rounded-progress`(4)                             |
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

| 컴포넌트                   | 쓰는 곳 (시안)                    | 고르는 값                                                                                                                                                                                                                                                                                                  |
| -------------------------- | --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Button`                   | 하단 고정 버튼 · 헤더 · 보고 완료 | `variant`: primary · secondary · text · subtle(회색 글자) / `size`: lg(56) · sm(44) / `fullWidth`                                                                                                                                                                                                          |
| `Badge`                    | 공식 정보 행 · 동네 안내 · 지도   | `kind`: official(공식) · citizen(시민 자가보고) · review(운영자 검토)                                                                                                                                                                                                                                      |
| `StatusWord`               | 홈 상태 카드                      | `status`                                                                                                                                                                                                                                                                                                   |
| `StatusGauge`              | 홈 상태 카드                      | `status` — 자료 부족이면 회색 · 점 없음                                                                                                                                                                                                                                                                    |
| `ProgressBar`              | 홈 자료 부족 상태의 참여 인원     | `value` · `max` · `label` — 참여 인원에만 쓰고 증상 비율에는 쓰지 않는다                                                                                                                                                                                                                                   |
| `ListRow`                  | 증상별 변화 · 공식 정보 행        | `kind`: data · link / `leading` · `trailing` / `divider`                                                                                                                                                                                                                                                   |
| `Section` · `SectionBand`  | 홈 섹션과 섹션 사이 8px 띠        | `title` / `layout`: page(화면 폭) · panel(모바일은 화면 여백, 태블릿부터 격자 · 패널 안이라 여백 없음)                                                                                                                                                                                                     |
| `Modal`                    | 보고 · 판단 기준 · 설치 안내      | `open` · `onClose` · `title` / `step` · `onBack` / `hideHeader` — 모바일 바텀시트, 태블릿 520 · 데스크톱 480 대화상자                                                                                                                                                                                      |
| `ToastRegion` · `useToast` | 보고 완료 되돌리기                | `toast` · `onAction` / `show` · `dismiss` (기본 5초)                                                                                                                                                                                                                                                       |
| `TabBar`                   | 모바일 · 태블릿 하단              | `current` / `navSearch`(링크 뒤 쿼리 — 둘러보기 동네) — 데스크톱에서 숨긴다                                                                                                                                                                                                                                |
| `AppHeader`                | 모든 사용자 화면 위               | `regionName` · `current` · 동네 · 알림 · 보고 콜백 / `reportLabel`(비회원 "로그인하고 보고하기") · `navSearch`                                                                                                                                                                                             |
| `IconButton`               | 알림 설정 · 닫기 · 이전 단계      | `label`(필수) · `icon`                                                                                                                                                                                                                                                                                     |
| `ChoiceButton`             | 보고 선택지                       | `label` · `hint`(설명으로 읽힘) / `selected`(여러 개 고르기 — `aria-pressed`) / `size`: lg(64) · md(56)                                                                                                                                                                                                    |
| `Callout`                  | 보고 수정 안내 · 홈 상단 알림     | `tone`: info · neutral / `icon`                                                                                                                                                                                                                                                                            |
| `TextField`                | 로그인 · 가입 입력칸              | `label`(위 14) · `hint` · `error`(문자열만, 아래 13, `aria-invalid` · `aria-describedby`) / `counter`(`n/10`, 넘으면 빨강) · `messageAction`(아래 줄 오른쪽) · `trailing`(칸 안 오른쪽, 남은 시간) / `type="password"` 면 보기 버튼 — 기본 1px 회색 · 포커스 2px 네이비 · 오류 2px danger · 꺼짐 회색 바탕 |
| `AlertBox`                 | 로그인 실패 · 안내 · 잠김         | `tone`: danger(`role=alert`) · info · neutral(`role=status`) / `action` — 여러 줄 문장 + 아래 버튼. 한 줄 안내는 `Callout`                                                                                                                                                                                 |
| `KakaoButton`              | 카카오로 계속하기                 | 높이 56 — 색은 카카오 가이드 값을 이 컴포넌트에만 둔다(토큰 밖 예외, 근거 주석)                                                                                                                                                                                                                            |
| `Checkbox`                 | 성인 확인 · 동의                  | `checked` · `onChange` · `label` / `size`: lg(17 굵게) · md(15) — 네이티브 체크 상자를 숨기고 모양만 그린다                                                                                                                                                                                                |
| 아이콘 (`icons.tsx`)       | 시안의 선 아이콘                  | `size` — 색은 글자색(`currentColor`)을 따른다                                                                                                                                                                                                                                                              |

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
- **단계 화면의 "뒤로" 는 기록을 쌓지 않는다.** 앱 안에서 앞 단계를 거쳐 왔으면 `router.back()`, 주소로 바로 들어와 앞 단계 기록이 없으면 `router.replace(앞 단계)` 다. 판별은 Provider 가 앱 안 이동 경로를 기록해서 한다 (`features/onboarding/onboarding-trail.ts`).
- **화면 위에 뜨는 시트 · 대화상자의 열림 상태는 주소 쿼리에 둔다** (`docs/design/SCREENS.md` 의 제안 라우트, 예: 판단 기준 `/?explain=1`). 새로고침 · 공유해도 같은 화면이 열린다. `src/lib/use-modal-param.ts` 를 쓴다. 단계마다 `push` 로 기록을 쌓아 휴대폰 뒤로 가기가 이전 단계 · 닫기로 이어지게 하고, 보낸 뒤 완료처럼 되돌아오면 안 되는 단계는 `replace` 로 바꾼다(`remove` 로 다른 쿼리를 같은 기록 항목에서 함께 지울 수 있다). `close` 는 이 훅이 쌓은 깊이만큼만 되돌린다 — 깊이는 `history.state` 에 두어 뒤로 가기로 단계를 되돌린 뒤 닫아도 홈 앞까지만 간다. 주소로 바로 들어와 쌓은 기록이 없으면 쿼리만 지운다. `router.push` 는 서버에 화면을 다시 요청하므로 쓰지 않는다.
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
