import { apiRequest } from '@/lib/api/client'
import type { DataSource } from '@/lib/data-source'

import { pickOfficialMock } from './mock'
import { type OfficialLatestResponse, toOfficialReport } from './official-map'
import type { OfficialReport } from './types'

/** 공식 정보 (docs/api-contract-draft.md "공식 정보", 백엔드 #213) */
export const OFFICIAL_LATEST_PATH = '/api/v1/official/latest'

/**
 * 공식 정보 화면(S08) 자료. 출처(`DataSource`)를 인자로 받아 백엔드나 목 데이터(`mock.ts`)로 답한다 — 출처는 서버 페이지가
 * `readServerDataSource()` 로 읽어 넘긴다(docs/conventions.md "데이터 출처").
 *
 * - 실데이터: `GET /api/v1/official/latest` 를 `auth: false` 로 부른다 — 인증이 필요 없는 공개 API 라 비회원 둘러보기에서도 부르고,
 *   만료된 access 를 실어 게이트웨이가 거절하는 일이 없게 한다. 응답은 `toOfficialReport` 로 옮긴다(무엇을 보일지 고르는 규칙은 그 파일).
 *   주소의 `?mock=` 은 듣지 않는다.
 * - 목데이터: `?mock=published|empty` 로 고른다(기본 `empty`).
 *
 * 요청 시간 제한 · 봉투 풀기는 API 계층이 맡는다. 실패(일시 장애 · 계약이 어긋난 응답)하면 Promise 를 거부한다 — 페이지의 오류 경계
 * (`app/error.tsx`, `다시 시도`)가 받는다. 지난 값 · 예시 값을 대신 보이지 않는다.
 */
export async function getLatestOfficial(
  source: DataSource,
  mock?: string | string[],
): Promise<OfficialReport> {
  if (source === 'api') {
    const response = await apiRequest<OfficialLatestResponse>(OFFICIAL_LATEST_PATH, {
      auth: false,
    })
    return toOfficialReport(response)
  }
  return pickOfficialMock(mock)
}
