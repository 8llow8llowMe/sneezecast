/* ── 서비스 안내 화면 (#193, 시안 없음) ─────────────────────────────────────────────────────────────
 *
 * 내 정보의 `모으는 정보와 보관 기간` · `데이터 출처` · `AI 사용 방식` 행이 여는 정적 안내다. 비회원도 본다.
 *
 * **문구는 앱이 이미 보여 준 동의 · 안내 문구와 구현된 백엔드 코드로 확인한 사실만 쓴다.** 새 사실을 지어내지 않는다.
 * 백엔드 설계 문서에만 있고 구현되지 않은 사실(집계 마감 · AI 초안 입력 등)은 쓰지 않는다 — 구현되면 근거와 함께 더한다.
 * 문장마다 근거를 옆에 적었다. 동의 문구가 바뀌면 이 문구도 함께 고친다(`info-screen.test.tsx` 가 일부를 맞춰 본다).
 * - 건강정보 동의 고지: `features/auth/health-consent.tsx` (Setup-4 · Consent-health-sheet)
 * - 가입 동의 항목: `features/auth/terms-screen.tsx` 의 `PRIVACY_CONSENT_DETAIL` (Setup-3 `개인정보 수집·이용`)
 * - 철회 · 탈퇴 대화상자: `features/me/confirm-dialog.tsx` (Confirm-consent-withdraw · Confirm-withdraw)
 * - 판단 기준: `features/home/explain-sheet.tsx` (S11) · 공식 정보: `features/official/official-screen.tsx` (S08)
 * - 동네 안내의 AI 고지: `features/notice/notice-screen.tsx` 의 `AI_DRAFT_DISCLOSURE` (S07) · 도메인 규칙: 루트 `CLAUDE.md`
 * - 로그인 기기 · 비밀번호 · 성인 확인: `backend/service/auth-service` 구현(아래 주석의 클래스)
 *
 * 문구 규칙(docs/design-guide.md "문구 규칙"): 한 문장에 한 가지 말, 쉼표로 두 문장을 잇지 않는다, 불안을 키우는 단어를 쓰지 않는다.
 * 목록 행 · 덧붙임은 한 줄에 한 문장이다.
 */

export const INFO_PAGE_KINDS = ['privacy', 'data-sources', 'ai'] as const

export type InfoPageKind = (typeof INFO_PAGE_KINDS)[number]

/**
 * 안내 화면 주소. 내 정보(`/me`) 아래에 두지만 **회원 가드를 걸지 않는다** — 회원만 보는 화면은 화면마다 `useMemberGate` 를 건다.
 * 내 정보 레이아웃의 조건 가드(`MeRequiredStepsGate` — 재동의 · 동네 다시 고르기)도 이 경로에서는 보내지 않는다(`isInfoPath`).
 * 돌아갈 곳 허용 목록(`NEXT_PATHS` · `BROWSE_NEXT_PATHS`)에는 넣지 않는다 — 로그인으로 보냈다 돌려보낼 일이 없고,
 * 머리줄 동네 이름은 동네 안내처럼 홈을 돌아갈 곳으로 연다(안내 내용은 동네와 무관하다).
 */
export const INFO_PATHS: Record<InfoPageKind, string> = {
  privacy: '/me/privacy',
  'data-sources': '/me/data-sources',
  ai: '/me/ai',
}

/** 안내 화면 경로인지. 쿼리 없는 경로(`usePathname`)로 본다 */
export function isInfoPath(pathname: string | null): boolean {
  return pathname !== null && Object.values(INFO_PATHS).includes(pathname)
}

export type InfoSection = {
  title: string
  /** 한 줄에 한 사실(한 문장). 1px 구분선 행으로 그린다 */
  items: readonly string[]
  /** 목록 아래 덧붙이는 문단(회색). 한 문단에 한 문장이다 */
  notes?: readonly string[]
}

export type InfoPage = {
  /** 화면 제목. 내 정보 행 제목과 같다 */
  title: string
  /** 제목 아래 한두 문장 */
  lead: string
  sections: readonly InfoSection[]
}

export const INFO_PAGES: Record<InfoPageKind, InfoPage> = {
  privacy: {
    title: '모으는 정보와 보관 기간',
    lead: '우리동네체온계가 모으는 정보와 보관 기간을 알려 드려요.',
    sections: [
      {
        title: '가입할 때 모으는 것',
        items: [
          // 가입 동의 `개인정보 수집·이용` 의 항목(terms-screen.tsx `PRIVACY_CONSENT_DETAIL`)
          '이메일',
          '닉네임',
          '내 동네(행정동)',
          // 이메일 가입만 받는다(카카오로만 로그인하면 없다 — member.password null, #61)
          '비밀번호(이메일로 가입할 때)',
          // S02-2 성인 확인. 가입 필수 확인 항목으로 남긴다(MemberConsentProcessor `SIGNUP_REQUIRED_CONSENTS` 의 AGE_OVER_19)
          '만 19세 이상 확인',
        ],
        // 비밀번호는 PasswordEncoder 로 바꿔 저장한다(AuthWebFacade `passwordEncoder.encode`)
        notes: ['비밀번호는 암호화해 저장해요.'],
      },
      {
        title: '로그인할 때 모으는 것',
        items: [
          // 기기 이름은 User-Agent 를 OS · 브라우저로 줄인 값(DeviceLabelResolver, AuthWebController 로그인)
          '로그인한 기기(브라우저 · 기기 종류)',
          // 세션의 createdAt · lastUsedAt(RedisRefreshSessionStoreAdapter)
          '로그인한 시각 · 마지막 사용 시각',
        ],
        notes: [
          // 로그인한 기기(`/me/devices`)
          '내 정보의 로그인한 기기 목록에 보여 드려요.',
          // 로그아웃은 세션을 지운다(AuthSessionProcessor `logout`). 세션은 refresh 만료를 TTL 로 둔다(RedisRefreshSessionStoreAdapter `PEXPIRE`)
          '로그아웃하거나 로그인이 만료되면 지워요.',
        ],
      },
      {
        title: '증상을 보고할 때 모으는 것',
        // 건강정보 동의 고지의 `모으는 것`(health-consent.tsx)
        items: ['증상 없음 또는 증상군(호흡기 · 장관)', '보고 주차', '보고 동네(행정동)'],
        notes: [
          // `모으는 이유`(health-consent.tsx)
          '행정동 단위로 주간 증상 보고 비율을 집계하는 데 써요.',
          // 건강정보 동의 체크 · `동의하지 않으면`(health-consent.tsx)
          '건강·증상 정보 처리에 따로 동의해야 보고할 수 있어요.',
        ],
      },
      {
        title: '모으지 않는 것',
        // 건강정보 동의 고지의 `모으지 않는 것`(health-consent.tsx)과 같은 순서
        items: ['이름', '연락처', '정확한 주소', 'GPS 위치', '자유 입력'],
        // 내 동네 바꾸기 안내(my-region-screen.tsx) · 위치 권한을 요청하지 않는다(frontend/CLAUDE.md)
        notes: ['내 동네는 위치 권한 없이 직접 골라요.'],
      },
      {
        title: '보관 기간',
        // `보관 기간`(health-consent.tsx). 개별 보고 52주는 확정(docs/design/auth/README.md)
        items: ['개별 보고는 52주 뒤 지워요.', '52주가 지나도 동네 집계값은 남아요.'],
      },
      {
        title: '동의를 철회하거나 탈퇴하면',
        items: [
          // `보관 기간` "동의를 철회하거나 탈퇴하면 바로 지워요"(health-consent.tsx) · 철회 대화상자(confirm-dialog.tsx)
          '건강정보 동의를 철회하면 보낸 보고를 모두 바로 지워요.',
          '동의를 철회하면 모든 기기에서 로그아웃돼요.',
          '다시 동의하기 전까지 보고할 수 없어요.',
          // 탈퇴 대화상자(confirm-dialog.tsx)
          '회원 탈퇴하면 보낸 보고를 바로 지워요.',
          '계정은 탈퇴 30일 뒤 완전히 지워요.',
        ],
        notes: ['건강정보 동의 철회와 회원 탈퇴는 내 정보에서 할 수 있어요.'],
      },
    ],
  },
  'data-sources': {
    title: '데이터 출처',
    lead: '우리동네체온계는 세 가지 자료를 써요. 시민 보고와 질병관리청 자료는 출처와 집계 단위가 달라 서로 섞지 않아요.',
    sections: [
      {
        title: '시민 주간 보고',
        items: [
          // 루트 CLAUDE.md "보고와 집계" — 성인 본인이 주 1회
          '성인 회원 본인이 한 주에 한 번 보고해요.',
          '행정동 단위로 모아요.',
          // 지표는 제보 건수가 아니라 참여자 대비 증상 보고 비율(루트 CLAUDE.md)
          '보고 건수가 아니라 그 주 참여자 중 증상을 보고한 비율을 보여 드려요.',
          '증상 없음 보고도 참여자에 넣어요.',
          // 판단 기준 `집계 방식` "1인 1주 1회 · 같은 주 수정은 마지막 보고만"(explain-sheet.tsx)
          '같은 사람의 같은 주 보고는 한 번만 집계해요.',
          '같은 주에 고쳤으면 마지막 보고로 집계해요.',
          // 판단 기준 `공개 조건` 참여 100명(explain-sheet.tsx · design-guide.md) · 보고가 불안정하면 자료 부족(루트 CLAUDE.md)
          '참여자가 100명보다 적거나 보고가 불안정한 주는 자료 부족으로 표시해요.',
          // 자료 부족이면 증상 비율 · 상태색을 숨기고 참여 진행 막대만(frontend/CLAUDE.md)
          '자료 부족인 주는 증상 비율과 상태 색을 보이지 않아요.',
          '자료 부족인 주에는 참여 현황만 보여 드려요.',
        ],
        // 판단 기준 아래 문구(explain-sheet.tsx)
        notes: ['시민이 스스로 보고한 자료라 진단이나 공식 유행 판단이 아니에요.'],
      },
      {
        title: '질병관리청 감시 자료',
        items: [
          '질병관리청이 발표한 감염병 감시 자료예요.',
          // 공식 자료는 행정동 단위가 아니다(official/types.ts)
          '행정동 단위 자료가 아니에요.',
          // 공식 정보 화면의 차이 안내(official-screen.tsx)
          '시민 자가보고와는 조사 대상 · 지역 단위 · 발표 시점이 달라요.',
          '두 정보는 화면에서 섞지 않아요.',
          // 공식 정보 화면의 출처 줄(official-screen.tsx `SourceLine`, 루트 CLAUDE.md "출처 · 집계 단위 · 기준 주")
          '출처 · 집계 단위 · 기준 주를 함께 밝혀요.',
        ],
        // 공식 정보 화면의 쉬운 요약 아래 문구(official-screen.tsx)
        notes: ['수치와 표현은 발표 원문을 기준으로 해요.'],
      },
      {
        title: '행정구역',
        items: [
          // 행정동 코드는 SGIS 8자리(region/types.ts) · 행정동 마스터 원천(backend/docs/entity-design.md §3-1)
          '행정동 이름과 코드는 SGIS(통계지리정보서비스) 행정구역 자료를 따라요.',
          // 폐지된 동네 다시 고르기(region-reselect-screen.tsx)
          '행정구역이 바뀌면 내 동네를 다시 고르도록 안내해요.',
        ],
      },
    ],
  },
  ai: {
    title: 'AI 사용 방식',
    // 내 정보 행 값 `안내문 초안만`(Settings 시안) · LLM 은 안내문 초안 한 곳에서만(backend/docs/modules.md).
    // 공식 정보의 쉬운 요약을 누가 쓰는지 정해지면 이 문장을 함께 고친다(docs/design/SCREENS.md "공식 정보")
    lead: 'AI는 동네 안내문의 초안을 쓰는 데에만 써요.',
    sections: [
      {
        title: 'AI가 하는 일',
        // 루트 CLAUDE.md "공식 정보와 안내"
        items: ['출처가 확인된 동네 집계값을 쉬운 문장으로 옮겨 안내문 초안을 써요.'],
      },
      {
        title: '운영자가 검토해요',
        items: [
          // 루트 CLAUDE.md "공식 정보와 안내" — 운영자 검토 · 승인, 승인 · 수정 · 발행 이력
          '운영자가 초안을 검토하고 필요하면 고쳐요.',
          '운영자가 승인한 동네에만 안내를 발행해요.',
          '승인 · 수정 · 발행 기록을 남겨요.',
          // 발행된 안내는 `운영자 검토` 배지와 AI 초안 고지를 늘 함께 보인다(notice-screen.tsx)
          '발행된 안내에는 운영자 검토 표시와 AI 초안이라는 안내를 함께 보여 드려요.',
        ],
      },
      {
        title: 'AI가 하지 않는 일',
        // 루트 CLAUDE.md — 진단 · 공식 유행 선언을 하지 않는다. 낱말은 판단 기준(explain-sheet.tsx)과 같게 둔다
        items: ['진단을 하지 않아요.', '공식 유행 판단을 하지 않아요.'],
        // 동네 안내의 AI 고지(notice-screen.tsx `AI_DRAFT_DISCLOSURE`)
        notes: ['안내는 진단이 아닌 참고 정보예요.', '증상이 심하면 의료기관에 문의하세요.'],
      },
    ],
  },
}
