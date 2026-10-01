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
│   ├── features/<도메인>/ # 화면별 UI (home, report, map, notice, official, admin …)
│   ├── lib/             # 로직. api/ 는 API 호출 계층, env.client.ts 는 공개 환경변수
│   ├── styles/          # tokens.css (토큰 정본) 와 토큰 검사 테스트
│   └── types/           # 공용 타입
└── docs/                # 규칙 정본과 시안 원본
```

- **`lib` · `components` · `types` 는 `features` 를 import 하지 않는다** (ESLint 로 막는다). 의존 방향은 `app → features → components · lib · types` 다.
- `src/features/`, `src/types/` 는 아직 없다. 처음 쓰는 PR 에서 만든다.

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

| 종류            | 유틸리티 예                                                                                                                                                            |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 색              | `text-fg` · `text-fg-sub` · `text-fg-muted` · `bg-bg` · `bg-section` · `border-divider` · `bg-brand` · `bg-info-bg` · `text-danger` · `bg-dim`                         |
| 상태            | `bg-status-normal` · `text-status-normal-text` (slight · high · insufficient 같은 형식)                                                                                |
| 글자            | `text-status` · `text-status-desktop` · `text-screen-title` · `text-section-title` · `text-body` · `text-body-strong` · `text-sub` · `text-caption` · `text-tab`       |
| 모서리          | `rounded-card` · `rounded-button` · `rounded-chip` · `rounded-sheet` · `rounded-dialog` · `rounded-small` · `rounded-bar`(2) · `rounded-progress`(4)                   |
| 테두리 · 투명도 | `border-hairline`(1) · `border-emphasis`(1.5) · `border-selected`(2) · `opacity-disabled`(0.5) · `stroke-opacity-inactive`(0.28) — `globals.css` 의 이름 있는 유틸리티 |
| 간격 · 크기     | `px-page-mobile` · `px-page-tablet` · `px-page-desktop` · `h-band` · `min-h-touch` · `size-touch` · `h-button` · `h-button-sm` · `h-tab-bar` · `h-header-desktop`      |
| 반응형          | `tablet:` (768px~) · `desktop:` (1280px~) — 잠정값, `globals.css` 주석 참고                                                                                            |

- 일반 간격(`p-2`, `gap-4`)은 Tailwind 4px 스케일을 그대로 쓴다.
- `[13px]`, `[#3D7EF0]` 같은 arbitrary value 와 raw hex 는 ESLint 가 막는다. 시안에 있는 값이면 `docs/design/tokens.json` → `src/styles/tokens.css` → `app/globals.css` 순서로 올린다.
- **토큰을 바꿀 때는 `tokens.json` 과 `tokens.css` 를 함께 고친다.** 어긋나면 `token-sync.test.ts` 가 실패한다.
- 상태는 항상 색과 글자 라벨을 함께 보인다. `자료 부족` 에서는 상태색을 쓰지 않는다.
- 상태 라벨·색 클래스는 `src/lib/status.ts` 에서만 가져온다. 화면에서 `'평소 수준'` 이나 `text-status-*` 를 다시 적지 않는다.
- 줄 높이 기본값은 `normal` 이다(시안과 맞춤, `globals.css` 주석). 시안이 줄 높이를 적은 곳만 `leading-*` 를 준다.

## 공통 컴포넌트

`src/components/` 에 있다. 도메인을 모르고, 시안의 같은 모양이 두 화면 이상에서 나오면 여기로 올린다.

| 컴포넌트                  | 쓰는 곳 (시안)                    | 고르는 값                                                                     |
| ------------------------- | --------------------------------- | ----------------------------------------------------------------------------- |
| `Button`                  | 하단 고정 버튼 · 헤더 · 보고 완료 | `variant`: primary · secondary · text / `size`: lg(56) · sm(44) / `fullWidth` |
| `Badge`                   | 공식 정보 행 · 동네 안내 · 지도   | `kind`: official(공식) · citizen(시민 자가보고) · review(운영자 검토)         |
| `StatusWord`              | 홈 상태 카드                      | `status`                                                                      |
| `StatusGauge`             | 홈 상태 카드                      | `status` — 자료 부족이면 회색 · 점 없음                                       |
| `ProgressBar`             | 홈 자료 부족 상태의 참여 인원     | `value` · `max` · `label` — 참여 인원에만 쓰고 증상 비율에는 쓰지 않는다      |
| `ListRow`                 | 증상별 변화 · 공식 정보 행        | `kind`: data · link / `leading` · `trailing` / `divider`                      |
| `Section` · `SectionBand` | 홈 섹션과 섹션 사이 8px 띠        | `title`                                                                       |

- `className` 은 바깥 배치(여백 · 정렬 · 폭)에만 쓴다. 색 · 크기 · 모서리는 컴포넌트가 고르는 값으로 바꾼다.
- 개발 서버의 `/dev/components` 에서 전부 볼 수 있다. 시안과 나란히 놓고 비교하는 용도이고 프로덕션에서는 404 다.

## 데이터와 환경변수

- 화면 코드(`app/`, `src/features/`, `src/components/`)에서 `fetch` 를 직접 부르지 않는다. API 호출은 `src/lib/api/` 로 모은다 (ESLint 로 막는다).
- 공개 환경변수는 `src/lib/env.client.ts` 에서만 읽는다. `process.env.NEXT_PUBLIC_X` 는 리터럴로 읽어야 빌드 때 치환된다.
- 목록은 `.env.example` 에 있다. 로컬 개발도 dev 게이트웨이(`https://api-dev.sneezecast.com`)를 쓴다.
- `localStorage` · `sessionStorage` 는 ESLint 가 막는다. 건강·증상 정보는 민감정보라 브라우저에 아무렇게나 남기지 않는다. 기기 토큰처럼 꼭 필요한 값은 저장 모듈 하나로 모으고 그 줄에 근거 주석을 남긴다.

## 테스트

- 파일은 대상 옆에 `*.test.ts(x)` 로 둔다.
- 기본 환경은 node 다. DOM 이 필요한 컴포넌트 테스트는 파일 맨 위에 `// @vitest-environment jsdom` 을 적어 그 파일만 켠다.
- 컴포넌트 테스트는 Testing Library(`@testing-library/react` · `user-event`)로 역할·이름으로 찾는다(`getByRole`). 렌더 정리는 `src/test/setup.ts` 가 DOM 환경일 때만 등록한다.
- 스타일은 클래스 이름(`classList`)으로 확인한다. 실제 픽셀 값은 `/dev/components` 를 브라우저로 열어 확인한다.
- jsdom 은 27 을 쓴다. 30 은 Node 22.22.2 이상을 요구해 `engines`(22.13 이상)와 맞지 않는다.

## 완료 전 확인

```bash
pnpm qa:verify   # format:check → lint → typecheck → test → build
```

통과하지 않으면 완료로 보고하지 않는다. 실패나 미실행은 PR 의 "검증 내역" 에 그대로 적는다.
