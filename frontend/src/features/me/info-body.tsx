import clsx from 'clsx'

import type { InfoPage, InfoSection } from './info-pages'

/**
 * 서비스 안내(S10, #193) 본문: 제목 아래 한두 문장 + 섹션들. 안내 화면(`info-screen.tsx`)과 가입 동의 위 시트(`info-sheet.tsx`, #229)가
 * 같이 쓴다 — 같은 안내를 두 곳에서 다르게 보이지 않게 한다.
 *
 * 섹션 제목은 놓이는 곳의 제목 아래 단계다. 화면은 머리줄 제목(h1) 아래라 h2, 시트는 대화상자 제목(h2) 아래라 h3 이다.
 */
export function InfoBody({
  page,
  sectionHeading = 'h2',
  className,
}: {
  page: InfoPage
  sectionHeading?: 'h2' | 'h3'
  className?: string
}) {
  return (
    <div className={clsx('flex flex-col gap-7', className)}>
      <p className="text-body leading-[1.6] text-fg-sub">{page.lead}</p>
      {page.sections.map((section) => (
        <InfoSectionBlock key={section.title} section={section} heading={sectionHeading} />
      ))}
    </div>
  )
}

/** 섹션 제목 17 굵게(내 정보 섹션과 같다) → 1px 구분선 행 → 회색 덧붙임 */
function InfoSectionBlock({
  section,
  heading: Heading,
}: {
  section: InfoSection
  heading: 'h2' | 'h3'
}) {
  return (
    <section className="flex flex-col">
      <Heading className="mb-1 text-section-title font-bold text-fg">{section.title}</Heading>
      <ul className="flex flex-col border-t border-divider">
        {section.items.map((item) => (
          <li key={item} className="border-b border-divider py-3 text-body leading-[1.6] text-fg">
            {item}
          </li>
        ))}
      </ul>
      {section.notes && (
        <div className="flex flex-col gap-1 pt-3">
          {section.notes.map((note) => (
            <p key={note} className="text-sub leading-[1.55] text-fg-sub">
              {note}
            </p>
          ))}
        </div>
      )}
    </section>
  )
}
