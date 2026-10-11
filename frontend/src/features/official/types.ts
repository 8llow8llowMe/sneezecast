/**
 * S08 공식 정보 화면 데이터. 질병관리청 감시 자료(`GET /api/v1/official/latest`, docs/api-contract-draft.md "공식 정보")를
 * 화면용으로 옮긴 모양이다. 문구(기준 주 · 기간 · 받은 시각 · 값)는 매핑(`official-map.ts`)이 만든다.
 *
 * - **실데이터 모양은 "수치 · 기준 주 · 출처" 다.** 발표 단계 · 쉬운 요약 · 발표일은 응답에 없어 이 타입에도 없다 — 지어내지 않는다.
 * - 표본감시 프로그램(인플루엔자 의사환자 분율 · 급성호흡기감염증 · 장관감염증)마다 한 묶음이다. 계열끼리 기준 주가 다를 수 있어
 *   출처 · 집계 단위 · 기준 주를 묶음마다 둔다. 전수신고(`NOTIFIABLE`)는 보일 내용이 정해지지 않아 묶음으로 옮기지 않는다.
 * - 값은 원값 그대로다(공공누리 제4유형). 전주 대비 같은 파생값을 만들지 않는다.
 *
 * **시민 자가보고 값은 이 타입에 없다.** 공식 정보와 시민 자가보고를 한 UI 요소에 섞지 않는다 (루트 CLAUDE.md "공식 정보와 안내").
 *
 * **보일 묶음이 없으면(`empty`, 이유 `reason`) 기준 주 · 값 필드가 타입에 아예 없다.** 화면 코드가 실수로 지어낸 값을 그릴 수 없게 한다.
 */
export type OfficialReport = PublishedOfficial | EmptyOfficial

export type PublishedOfficial = {
  status: 'published'
  /** 표본감시 프로그램 묶음. 하나 이상이고 응답 순서(인플루엔자 → 급성호흡기 → 장관)다 */
  programs: readonly OfficialProgramView[]
}

export type EmptyOfficial = {
  status: 'empty'
  /** `none`: 받은 자료가 없다(`{ programs: [] }`). `unshown`: 받았지만 이 화면이 보이는 표본감시 자료가 없다(전수신고만 등) */
  reason: 'none' | 'unshown'
}

export type OfficialProgramView = {
  /** 프로그램 코드. 묶음 키 */
  code: string
  /** 프로그램 이름 (응답 `program.name`). 예: "급성호흡기감염증" */
  name: string
  /** 출처 (응답 `source.name`). 예: "질병관리청 표본감시" */
  sourceName: string
  /** 집계 단위 (응답 `regionName`). 예: "전국" — 행정동 단위가 아니다 */
  regionLabel: string
  /** 기준 주 — 질병관리청 주차(일요일 시작). ISO 주와 섞지 않는다. 예: "2026년 38주" */
  weekLabel: string
  /** 기준 주의 기간. 예: "9월 13일~19일". 날짜를 읽을 수 없으면 null */
  periodLabel: string | null
  /** 우리가 그 값을 받은 시각(`syncedAt`, 한국 시각). 발표일이 아니다. 예: "9월 25일 06:00". 읽을 수 없으면 null */
  syncedLabel: string | null
  /** 값의 단위 (응답 `metric.name`). 예: "발생 수" */
  metricLabel: string
  /** 보일 줄. 하나 이상이다 */
  rows: readonly OfficialRow[]
  /** 일부만 집계된 주 · 줄을 골라 줄였음을 알리는 문장. 둘 다 아니면 null */
  note: string | null
  /** 원문 주소 (질병관리청 감염병포털 화면, 외부 · 새 창). 확인된 주소가 없으면 null — `원문 보기` 를 그리지 않는다 */
  sourceUrl: string | null
}

export type OfficialRow = {
  /** 병원체 이름 · 연령대 · "합계". 예: "호흡기세포융합바이러스", "7~12세" */
  label: string
  /** 원값과 단위. 원천이 빈 칸이면 "값 없음"(0 과 다르다). 예: "152건", "12.3" */
  value: string
}
