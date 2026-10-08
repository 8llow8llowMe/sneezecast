import type { District, ReselectCandidate } from './types'

/**
 * 행정동 목 데이터. **예시 값이다.** 코드는 SGIS 형식(8자리)만 맞춘 것이라 실제 경계와 대조하지 않았다.
 *
 * 데이터 출처가 목(`mock`)일 때 `region-client.ts` 가 쓴다(실데이터는 백엔드 `GET /api/v1/districts`, docs/conventions.md "데이터 출처").
 * 이름이 같은 동(신사동)과 번호가 붙은 동(역삼1동 · 역삼2동)을 넣어 시군구로 가려 보이는지 확인한다.
 */
export const DISTRICT_MOCKS: readonly District[] = [
  { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' },
  { code: '11680650', name: '역삼2동', sigungu: '서울특별시 강남구' },
  { code: '11680510', name: '신사동', sigungu: '서울특별시 강남구' },
  { code: '11620685', name: '신사동', sigungu: '서울특별시 관악구' },
  { code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' },
  { code: '11440680', name: '합정동', sigungu: '서울특별시 마포구' },
  { code: '11440690', name: '망원1동', sigungu: '서울특별시 마포구' },
  { code: '11440700', name: '망원2동', sigungu: '서울특별시 마포구' },
  { code: '11110530', name: '사직동', sigungu: '서울특별시 종로구' },
  { code: '11110540', name: '삼청동', sigungu: '서울특별시 종로구' },
  { code: '11305595', name: '수유1동', sigungu: '서울특별시 강북구' },
  { code: '26350525', name: '우동', sigungu: '부산광역시 해운대구' },
  { code: '26350530', name: '중동', sigungu: '부산광역시 해운대구' },
  { code: '41135580', name: '정자1동', sigungu: '경기도 성남시 분당구' },
  { code: '41135590', name: '정자2동', sigungu: '경기도 성남시 분당구' },
]

/**
 * 목 회원의 관심 동네 예시 (`interest-region-client.ts`). 시안(Settings)의 `2곳` 에 맞춰 둘이고, 상한 재현(`full`)은 셋째를 더한다.
 * **예시 값이다** — 검색 목록(`DISTRICT_MOCKS`)에 있는 동네라 지우고 다시 더할 수 있다.
 */
export const INTEREST_REGION_EXAMPLES: readonly District[] = [
  { code: '11440690', name: '망원1동', sigungu: '서울특별시 마포구' },
  { code: '11110540', name: '삼청동', sigungu: '서울특별시 종로구' },
  { code: '26350525', name: '우동', sigungu: '부산광역시 해운대구' },
]

/**
 * 행정구역 개편으로 폐지된 동네 (Setup-1-reselect 시안의 `○○1동`). **시안 예시 값이다** — 이름 · 시군구를 시안 그대로 두고
 * 코드는 실제 행정동과 겹치지 않게 지어낸 8자리다. 목 회원의 폐지된 동네 · `?mock-required=region` 덮어쓰기가 쓴다.
 * 검색(`DISTRICT_MOCKS`)에는 넣지 않는다 — 폐지된 동은 고를 수 없다.
 */
export const ABOLISHED_DISTRICT_EXAMPLE: District = {
  code: '99990110',
  name: '○○1동',
  sigungu: '○○시 ○○구',
}

/**
 * 행정동 서비스가 모르는 코드 (관심 동네 목 재현 `?mock-interest-regions=abolished`). 서버는 이 코드의 이름 · 시군구를 null 로 준다.
 * 실제 행정동과 겹치지 않게 지어낸 8자리다 — 화면에 보이지 않는다(이름 자리에 `없어진 동네`).
 */
export const UNKNOWN_DISTRICT_CODE_EXAMPLE = '99990120'

/**
 * 폐지된 동네 코드 → 다시 고를 후보 (Setup-1-reselect 시안의 세 줄). **시안 예시 값이다.**
 * 연동 때 백엔드 #60 의 재선택 유도 응답으로 바꾸고 이 값을 지운다.
 */
export const RESELECT_CANDIDATE_MOCKS: Readonly<Record<string, readonly ReselectCandidate[]>> = {
  [ABOLISHED_DISTRICT_EXAMPLE.code]: [
    { code: '99990111', name: '○○새1동', sigungu: '○○시 ○○구', partOfAbolished: true },
    { code: '99990112', name: '○○새2동', sigungu: '○○시 ○○구', partOfAbolished: true },
    { code: '99990120', name: '○○2동', sigungu: '○○시 ○○구', partOfAbolished: false },
  ],
}
