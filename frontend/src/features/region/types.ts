/**
 * 행정동. 사용자가 직접 고른다 — 위치 권한 · GPS 로 정하지 않는다 (루트 CLAUDE.md "보고와 집계").
 *
 * 홈 · 첫 진입 · 지도 · 내 정보가 같이 쓰므로 온보딩이 아니라 region 도메인에 둔다.
 */
export type District = {
  /** SGIS 행정동 코드 8자리. 예: "11680640" */
  code: string
  /** 행정동 이름. 예: "역삼1동" */
  name: string
  /** 시도 · 시군구. 이름이 같은 동을 가려 보인다. 예: "서울특별시 강남구" */
  sigungu: string
}

/**
 * 폐지된 동네를 다시 고를 때의 후보 (Setup-1-reselect). 서버가 옛 동네 코드로 알려 준다(백엔드 #60 재선택 유도 응답).
 * `partOfAbolished` 는 옛 동네의 일부를 이어 받은 동인지다 — 화면이 `옛 ○○1동 일부` 로 적는다.
 * 이어 받지 않은 후보(시안의 `○○2동`)를 서버가 어떤 기준으로 줄지는 #60 과 정한다.
 */
export type ReselectCandidate = District & { partOfAbolished: boolean }
