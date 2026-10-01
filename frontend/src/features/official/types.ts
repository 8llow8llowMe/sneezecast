/**
 * S08 공식 정보 화면 데이터. 질병관리청 감시 자료(API 초안 `GET /api/official/latest`, docs/api-contract-draft.md)를
 * 화면용으로 옮긴 모양이다. 날짜 · 주차 문구는 API 연동 때 매핑 계층이 만든다 — 지금은 목 데이터가 채운다.
 *
 * **시민 자가보고 값은 이 타입에 없다.** 공식 정보와 시민 자가보고를 한 UI 요소에 섞지 않는다 (루트 CLAUDE.md "공식 정보와 안내").
 *
 * **받은 발표가 없으면(`empty`) 기준 주 · 단계 · 요약 필드가 타입에 아예 없다.** 화면 코드가 실수로 지어낸 값을 그릴 수 없게 한다.
 */
export type OfficialReport = OfficialCommon & (PublishedOfficial | EmptyOfficial)

type OfficialCommon = {
  /** 집계 단위(지역). 예: "전국". 공식 자료는 행정동 단위가 아니다 */
  regionLabel: string
}

export type PublishedOfficial = {
  status: 'published'
  /** 기준 주 (모바일). 질병관리청 주차 그대로. 예: "47주" */
  weekLabel: string
  /** 기준 주의 기간 (태블릿 · 데스크톱). 예: "11월 17일~23일" */
  periodLabel: string
  /** 발표일. 예: "11월 21일 발표" */
  announcedLabel: string
  /** 감염병 이름. 예: "인플루엔자" */
  disease: string
  /** 조사 방식. 예: "의료기관 표본감시" — 시민 자가보고와 무엇이 다른지 알리는 문장에 쓴다 */
  surveyLabel: string
  /** 발표 단계. 예: "유행주의보 발령 중" — 발표 원문의 표현을 그대로 쓴다 */
  stage: string
  /** 쉬운 요약. 한 줄에 한 문장이다 */
  summary: readonly string[]
  /** 발표 원문 주소 (외부, 새 창) */
  sourceUrl: string
}

export type EmptyOfficial = {
  status: 'empty'
}
