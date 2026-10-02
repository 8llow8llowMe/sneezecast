import type { Metadata } from 'next'
import Link from 'next/link'

import { InfoIcon } from '@/components/icons'
import { StateHeader } from '@/components/state-header'
import { TabBar } from '@/components/tab-bar'

export const metadata: Metadata = { title: '없는 화면' }

/**
 * 없는 화면 (404). 주소를 모르거나 화면이 `notFound()` 를 부르면 Next 가 이 화면을 404 로 보낸다.
 * 시안이 없어 오류 화면(State-error)과 같은 모양 — 가운데 아이콘 · 제목 · 설명 · 버튼 — 에 홈으로 가는 링크를 둔다.
 *
 * 탭바(모바일 · 태블릿)와 데스크톱 머리줄(서비스명 · 메뉴)을 그려 다른 메뉴로 갈 수 있게 하고, 지금 메뉴는 표시하지 않는다.
 * 앱 안 링크로 들어와도 문서를 새로 읽지 않아 목 로그인이 이어진다. 둘러보기 동네(`?region=`)는 이 화면에서 모르므로 붙이지 않는다.
 */
export default function NotFound() {
  return (
    <div className="flex min-h-dvh flex-col bg-bg">
      <StateHeader current={null} skeleton={false} />

      <main className="flex grow flex-col items-center justify-center gap-3 px-8 text-center tablet:p-10 desktop:p-0">
        <InfoIcon size={48} strokeWidth={1.5} className="text-fg-muted" />
        <h1 className="text-screen-title font-bold text-fg tablet:text-dialog-title">
          화면을 찾을 수 없어요
        </h1>
        <p className="text-body leading-[1.55] text-fg-sub tablet:text-body-large">
          주소가 바뀌었거나 없는 화면이에요.
        </p>
        <Link
          href="/"
          className="mt-2 inline-flex h-button-sm items-center justify-center rounded-button bg-brand px-5 text-body font-semibold text-bg"
        >
          홈으로 가기
        </Link>
      </main>

      <TabBar current={null} className="sticky bottom-0" />
    </div>
  )
}
