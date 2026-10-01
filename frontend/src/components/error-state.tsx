import type { MainNavKey } from '@/lib/nav'

import { Button } from './button'
import { InfoIcon } from './icons'
import { StateHeader } from './state-header'
import { TabBar } from './tab-bar'

/**
 * 정보를 불러오지 못함 (State-error · -T · -D). 가운데 안내 아이콘 · 제목 · 설명 · `다시 시도` 만 둔다.
 * 지난 값이나 예시 값을 대신 보이지 않는다 — 보일 자료가 없으면 없다고만 말한다.
 *
 * `nav` 는 주요 메뉴 화면일 때 현재 메뉴다. 있으면 탭바(모바일 · 태블릿)와 데스크톱 머리줄(서비스명 · 메뉴)을 그려
 * 다른 메뉴로 갈 수 있게 한다. 시안 머리줄의 동네 이름 · 알림 · 보고 버튼은 불러오지 못한 화면에서 알 수 없어 그리지 않는다.
 *
 * 설명은 모바일에서 두 줄(문장마다 줄바꿈), 태블릿부터 한 줄이다. 제목 20 → 24, 설명 15 → 16.
 */
export function ErrorState({
  onRetry,
  nav = null,
}: {
  /** `다시 시도`. 라우트 오류 화면은 Next 의 `retry`(다시 불러와 그리기)를 넘긴다 */
  onRetry: () => void
  nav?: MainNavKey | null
}) {
  return (
    <div className="flex min-h-dvh flex-col bg-bg">
      {nav && <StateHeader current={nav} skeleton={false} />}

      <main className="flex grow flex-col items-center justify-center gap-3 px-8 text-center tablet:p-10 desktop:p-0">
        <InfoIcon size={48} strokeWidth={1.5} className="text-fg-muted" />
        <h1 className="text-screen-title font-bold text-fg tablet:text-dialog-title">
          정보를 불러오지 못했어요
        </h1>
        <p className="text-body leading-[1.55] text-fg-sub tablet:text-body-large">
          잠시 후 다시 시도해 주세요.
          <br className="tablet:hidden" /> 보고는 연결되면 다시 보낼 수 있어요.
        </p>
        <Button variant="secondary" size="sm" onClick={onRetry} className="mt-2">
          다시 시도
        </Button>
      </main>

      {nav && <TabBar current={nav} className="sticky bottom-0" />}
    </div>
  )
}
