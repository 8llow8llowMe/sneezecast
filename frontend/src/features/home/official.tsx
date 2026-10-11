import Link from 'next/link'

import { Badge } from '@/components/badge'
import { ChevronRightIcon } from '@/components/icons'
import { ListRow } from '@/components/list-row'

/**
 * 질병관리청 공식 정보. **시민 자가보고와 한 UI 요소에 섞지 않는다** — 상태 카드와 떨어진 자리에
 * `공식` 배지 · 출처 · 집계 단위 · 기준을 밝혀 따로 그린다 (루트 CLAUDE.md "공식 정보와 안내").
 */

/** 공식 정보 화면 (S08) */
const OFFICIAL_PATH = '/official'

/**
 * 목데이터 홈에서 공식 정보 화면을 열 때 고르는 목 상태. 공식 정보 화면의 목 기본값은 `받은 자료 없음`이라, 수치가 있는 예시 응답
 * (`?mock=published`)으로 이어 목 홈에서도 공식 정보 예시를 볼 수 있게 한다. 실데이터는 목 상태를 붙이지 않는다
 */
const OFFICIAL_MOCK_PATH = `${OFFICIAL_PATH}?mock=published`

/**
 * 공식 정보 이동 행 (모바일은 띠 사이, 태블릿 · 데스크톱은 헤더 아래). S08 공식 정보 화면(`/official`)으로 간다.
 * 발표 단계 · 감염병 요약은 두지 않는다 — 공식 정보 화면이 질병관리청 표본감시 수치를 그대로 보이고 단계를 매기지 않아,
 * 홈은 출처와 갈 곳만 밝힌다. 목 · 실데이터가 같은 모양이다.
 * `navSearch`(둘러보기 동네 `region=<코드>`)는 메뉴 링크처럼 뒤에 붙여 공식 정보 화면에서도 동네를 잃지 않게 한다.
 */
export function OfficialRow({
  navSearch,
  mock = false,
}: {
  navSearch?: string | undefined
  /** 목데이터 홈이면 공식 정보 목 예시(`?mock=published`)로 잇는다 */
  mock?: boolean
}) {
  return (
    <Link
      href={officialHref(navSearch, mock)}
      className="block bg-bg px-page-mobile tablet:px-page-tablet desktop:px-page-desktop"
    >
      <ListRow
        kind="link"
        leading={<Badge kind="official" />}
        title="질병관리청 공식 정보"
        description="감염병 발생 현황 · 시민 자가보고와 따로 봐요"
        trailing={<ChevronRightIcon className="shrink-0 text-fg-muted" />}
      />
    </Link>
  )
}

/**
 * 공식 정보 화면 주소. 홈의 두 진입점(공식 정보 행 · 동네 안내의 `질병관리청 공식 정보 보기`)이 같은 주소를 쓴다.
 * `navSearch` 는 주소 뒤에 붙이고, 주소에 이미 쿼리가 있으면(목 `?mock=`) `&` 로 잇는다
 */
export function officialHref(navSearch?: string, mock = false): string {
  const href = mock ? OFFICIAL_MOCK_PATH : OFFICIAL_PATH
  if (!navSearch) return href
  return `${href}${href.includes('?') ? '&' : '?'}${navSearch}`
}
