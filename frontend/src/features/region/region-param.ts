import { findDistrict } from './region-client'
import type { District } from './types'

/**
 * 주소 쿼리 `?region=<행정동 코드>` 의 값을 행정동으로. 둘러보기(S02-1)에서 고른 동네를 홈이 받을 때 쓴다.
 *
 * 값이 여러 개(`?region=a&region=b`)면 첫 값을 쓴다. 없거나 비었거나 모르는 코드면 null 이다 —
 * 부르는 쪽은 null 이면 원래 동네를 그대로 둔다.
 */
export function districtFromParam(value: string | string[] | undefined): Promise<District | null> {
  const code = Array.isArray(value) ? value[0] : value
  return code ? findDistrict(code) : Promise.resolve(null)
}
