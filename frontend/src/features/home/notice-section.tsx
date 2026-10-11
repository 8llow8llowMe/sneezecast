import Link from 'next/link'

import { Badge } from '@/components/badge'
import { Section } from '@/components/section'
import { navHref } from '@/lib/nav'

import type { PublishedNotice } from './types'

/**
 * 우리 동네 안내 섹션. **운영자가 검토 · 발행한 주에만** 안내를 보인다 (루트 CLAUDE.md "공식 정보와 안내").
 * 발행 전이면 공식 예방수칙으로 이어 준다. 안내 링크는 동네 안내 화면(S07 `/notice/[region]/[week]`)으로 간다.
 *
 * 실데이터는 집계 응답의 요약(제목 · 발행일)만 알아 `운영자 검토` 배지 · 발행일 · 제목만 그린다. 할 일 목록 · 근거는 시안 예시(목)에만
 * 있고, 동네 안내 화면으로 가는 링크는 그 화면의 안내 내용이 목이라 본문 연동(#256) 때 연다(`types.ts` 의 `PublishedNotice`).
 */
export function NoticeSection({
  notice,
  officialHref = '/official',
  navSearch,
}: {
  notice: PublishedNotice | null
  /** 안내 링크 뒤에 붙일 둘러보기 동네(`region=<코드>`) — 메뉴 링크처럼 동네를 잃지 않게 한다 */
  navSearch?: string | undefined
  /** `공식 예방수칙 보기` 가 갈 곳. 홈은 공식 정보 행과 같은 주소(목 상태 · 둘러보기 동네 포함)를 넘긴다 */
  officialHref?: string
}) {
  return (
    <Section title="우리 동네 안내" layout="panel">
      {notice ? (
        <div className="flex flex-col gap-1 pt-1">
          <div className="mb-1 flex items-center gap-2">
            <Badge kind="review" />
            <span className="text-sub text-fg-sub">{notice.publishedLabel}</span>
          </div>
          {notice.title && (
            <p className="text-body leading-normal font-semibold text-fg">{notice.title}</p>
          )}
          {notice.items && (
            <ol className="flex flex-col">
              {notice.items.map((item, index) => (
                <li key={item} className="flex gap-2.5 py-1.5 text-body leading-normal text-fg">
                  <span aria-hidden="true" className="w-5 shrink-0 font-bold text-brand">
                    {index + 1}
                  </span>
                  <span>{item}</span>
                </li>
              ))}
            </ol>
          )}
          {/* 근거 · 링크는 목에만 있다(실데이터는 #256 전까지 둘 다 없다) */}
          {notice.source && notice.href && (
            <div className="flex items-center justify-between pt-2">
              <span className="text-sub text-fg-sub">근거: {notice.source}</span>
              <Link
                href={navHref(notice.href, navSearch)}
                className="text-sub font-semibold text-brand"
              >
                안내 전체 보기
              </Link>
            </div>
          )}
        </div>
      ) : (
        <div className="flex min-h-12 items-center justify-between gap-3">
          <span className="text-body text-fg-sub">이번 주 발행된 동네 안내가 없어요</span>
          <Link href={officialHref} className="shrink-0 text-sub font-semibold text-brand">
            공식 예방수칙 보기
          </Link>
        </div>
      )}
    </Section>
  )
}
