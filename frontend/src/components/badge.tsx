import clsx from 'clsx'

/**
 * 정보 출처 배지 (tokens.json `badges`).
 * - official: 질병관리청 공식 정보 — 네이비 바탕 흰 글씨
 * - citizen: 시민 자가보고 — 회색 테두리
 * - review: 운영자가 검토·발행한 안내 — 네이비 테두리
 *
 * 공식 정보와 시민 자가보고는 한 UI 요소에 섞지 않는다 (design-guide.md "범위"). 출처마다 배지를 따로 단다.
 */
export type BadgeKind = 'official' | 'citizen' | 'review'

const BADGE_LABEL: Record<BadgeKind, string> = {
  official: '공식',
  citizen: '시민 자가보고',
  review: '운영자 검토',
}

const BADGE_CLASS: Record<BadgeKind, string> = {
  official: 'bg-brand text-bg py-0.75 font-bold',
  citizen: 'border-hairline border-muted-bar text-fg-sub py-0.5 font-semibold',
  review: 'border-emphasis border-brand text-brand py-0.5 font-bold',
}

export function Badge({ kind, className }: { kind: BadgeKind; className?: string }) {
  return (
    <span
      className={clsx(
        'inline-flex shrink-0 items-center rounded-chip px-2 text-caption',
        BADGE_CLASS[kind],
        className,
      )}
    >
      {BADGE_LABEL[kind]}
    </span>
  )
}
