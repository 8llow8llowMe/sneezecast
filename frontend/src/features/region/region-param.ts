import { ApiError } from '@/lib/api/api-error'
import type { DataSource } from '@/lib/data-source'

import { findDistrict } from './region-client'
import type { District } from './types'

/**
 * 주소 쿼리 `?region=<행정동 코드>` 의 값을 행정동으로. 둘러보기(S02-1)에서 고른 동네를 홈이 받을 때 쓴다.
 * 출처(`source`)는 서버 페이지가 `readServerDataSource()` 로 읽어 넘긴다.
 *
 * 값이 여러 개(`?region=a&region=b`)면 첫 값을 쓴다. 없거나 비었거나 모르는 코드 · 폐지된 코드면 null 이다 —
 * 부르는 쪽은 null 이면 원래 동네를 그대로 둔다.
 *
 * **API 가 실패해도(일시 장애 등) null 이다.** 이 쿼리는 둘러보던 동네를 이어 주는 덧붙임이라, 못 찾았다고 화면 전체를
 * 오류로 바꾸지 않고 원래 동네로 그린다. 프로그램 오류(`ApiError` 가 아닌 것)는 숨기지 않고 던진다.
 */
export async function districtFromParam(
  value: string | string[] | undefined,
  source: DataSource,
): Promise<District | null> {
  const code = Array.isArray(value) ? value[0] : value
  if (!code) return null
  try {
    const found = await findDistrict(code, source)
    if (!found?.active) return null
    return { code: found.code, name: found.name, sigungu: found.sigungu }
  } catch (error) {
    if (error instanceof ApiError) return null
    throw error
  }
}
