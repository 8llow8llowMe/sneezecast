import Link from 'next/link'

import { Badge } from '@/components/badge'
import { ChevronRightIcon } from '@/components/icons'
import { ListRow } from '@/components/list-row'
import { Section } from '@/components/section'

import type { OfficialSummary } from './types'

/**
 * 질병관리청 공식 정보. **시민 자가보고와 한 UI 요소에 섞지 않는다** — 상태 카드와 떨어진 자리에
 * `공식` 배지 · 출처 · 집계 단위 · 기준을 밝혀 따로 그린다 (루트 CLAUDE.md "공식 정보와 안내").
 */

/** 공식 정보 이동 행 (모바일은 띠 사이, 태블릿 · 데스크톱은 헤더 아래) */
export function OfficialRow({ official }: { official: OfficialSummary }) {
  return (
    <Link
      href={official.href}
      className="block bg-bg px-page-mobile tablet:px-page-tablet desktop:px-page-desktop"
    >
      <ListRow
        kind="link"
        leading={<Badge kind="official" />}
        title={official.headline}
        description={official.sourceLine}
        trailing={<ChevronRightIcon className="shrink-0 text-fg-muted" />}
      />
    </Link>
  )
}

/** 공식 정보 요약 섹션 (태블릿 2열 격자의 한 칸) */
export function OfficialPanel({ official }: { official: OfficialSummary }) {
  return (
    <Section title="공식 정보" layout="panel">
      <dl className="flex flex-col">
        <OfficialItem term={official.disease} detail={official.stage} />
        <OfficialItem term="기준" detail={official.basis} />
      </dl>
      <p className="pt-2.5 text-caption leading-normal text-fg-sub">
        의료기관 표본감시 자료라 시민 자가보고와 조사 대상·지역 단위·발표 시점이 달라요
      </p>
    </Section>
  )
}

function OfficialItem({ term, detail }: { term: string; detail: string }) {
  return (
    <div className="flex min-h-13 items-center justify-between border-b border-divider">
      <dt className="text-body text-fg">{term}</dt>
      <dd className="text-sub font-medium text-fg-sub">{detail}</dd>
    </div>
  )
}
