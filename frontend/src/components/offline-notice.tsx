import { formatMonthDayTime } from '@/lib/format'

import { Callout } from './callout'
import { InfoIcon } from './icons'

/**
 * 오프라인 안내 띠 (State-offline · -T · -D). 화면 전체를 바꾸지 않고 홈 위쪽 알림 줄(Home 의 `notice`)에
 * "오프라인이에요. 11월 19일 09:00에 받은 정보예요" 를 띄운다. 아래 화면은 이미 받은 정보를 그대로 둔다.
 *
 * - `offline` 은 부르는 쪽이 `useOnline()`(src/lib/use-online.ts)으로 정한다. 하이드레이션 전에는 늘 온라인이다
 * - `receivedAt` 은 지금 보이는 정보를 받은 시각이다. 모르거나 읽을 수 없으면 시각 문장을 빼고 "오프라인이에요" 만 보인다 —
 *   시각을 지어내지 않는다
 *
 * **바깥 영역(`aria-live`)은 온라인일 때도 늘 그려 둔다.** 스크린리더는 이미 있던 영역의 내용이 바뀔 때 읽는다(토스트와 같은 규칙).
 * 위치 · 여백(시안: 위 12 · 좌우 20)은 `className` 으로 부르는 쪽이 준다 — 안내 상자에 붙는다.
 */
export function OfflineNotice({
  offline,
  receivedAt = null,
  className,
}: {
  offline: boolean
  receivedAt?: string | Date | null
  className?: string
}) {
  const time = receivedAt === null ? null : formatMonthDayTime(receivedAt)
  return (
    <div aria-live="polite">
      {offline && (
        <Callout
          tone="neutral"
          icon={<InfoIcon className="shrink-0 text-fg-sub" />}
          className={className ?? ''}
        >
          {time ? `오프라인이에요. ${time}에 받은 정보예요` : '오프라인이에요'}
        </Callout>
      )}
    </div>
  )
}
