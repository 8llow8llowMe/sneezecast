import type { HomeWeekly } from '@/features/home/types'

/**
 * 지도(S04) 화면 데이터. 동네 하나의 이번 주는 홈과 같은 모양(`HomeWeekly`)이다 —
 * **`자료 부족` 이면 증상 비율 · 기준선 · 증상별 변화가 타입에 없어** 지도 · 동네 정보가 수치를 그릴 수 없다.
 *
 * 연동 때 집계 API(동네별 이번 주 상태)와 행정동 경계(SGIS)를 이 모양으로 옮긴다 (docs/design/SCREENS.md "지도").
 */
export type MapWeek = {
  /** 예: "11월 3주" (지도 위 기준 표시) */
  weekLabel: string
  /** 처음 고른 동네(내 동네 · 둘러보기 동네)의 코드 */
  mineCode: string
  /** 처음 고른 동네의 이름. 머리줄 동네 버튼에 쓴다 */
  mineName: string
  /**
   * 지도에 칠할 동네. 비어 있으면 이번 주 동네 자료가 아직 없다(자료 없음).
   * 비어 있지 않으면 처음 고른 동네(`mineCode` — 찾아서 고른 동네 포함)를 꼭 담는다. 그 동네의 이번 주 보고가 적거나 없으면
   * 수치 없이 자료 부족으로 담는다 — 빠지면 화면은 목록의 첫 동네를 고른 동네로 보인다
   */
  districts: MapDistrict[]
}

export type MapDistrict = {
  /** 행정동 코드 (SGIS 8자리) */
  code: string
  /** 그 동네의 이번 주. 이름은 `week.regionName` 이다 */
  week: HomeWeekly
}
