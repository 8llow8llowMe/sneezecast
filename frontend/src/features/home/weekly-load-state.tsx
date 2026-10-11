import { AlertBox } from '@/components/alert-box'
import { Button } from '@/components/button'

import type { PendingWeeklyLoad } from './use-home-weekly'

/**
 * 실데이터 홈 · 지도가 이번 주 동네 집계를 아직 그릴 수 없을 때 상태 카드 · 증상별 변화 · 동네 안내 자리(지도는 지도 · 동네 정보
 * 자리)에 두는 안내(시안 없음 — 다른 연동 화면의 불러오는 중 문장 · 빨강 상자 + `다시 시도` 와 같은 모양). 지난 값 · 예시 값을
 * 대신 보이지 않는다.
 *
 * - 읽는 중: 회색 문장(`role="status"`)
 * - 읽지 못함: 빨강 상자 + `다시 시도`(내 동네 · 집계 중 실패한 쪽을 다시 읽음). `pickOnFailure` 면 옆에 `동네 고르기` 도 둔다 —
 *   모바일 지도는 머리줄 · 찾기 칸이 없어 이 상자가 동네를 바꿀 유일한 길이다(홈은 머리줄 동네 이름이 늘 있어 두지 않는다)
 * - 볼 동네 없음(비회원이 둘러볼 동네를 고르지 않음 · 내 동네를 정하지 않음 · 집계가 모르는 코드): 파랑 상자 + `동네 고르기`
 *   (머리줄 동네 이름과 같은 둘러볼 동네 고르기)
 */
export function WeeklyLoadState({
  load,
  onPickRegion,
  className = 'mx-5 mt-4 mb-5 tablet:col-span-2 tablet:m-0',
  pickOnFailure = false,
}: {
  load: PendingWeeklyLoad
  onPickRegion: () => void
  /** 읽지 못함에도 `동네 고르기` 를 둔다(지도) */
  pickOnFailure?: boolean
  /** 바깥 여백 · 격자 자리. 기본값은 홈 격자 자리다 */
  className?: string
}) {
  return (
    <div className={className}>
      {load.status === 'loading' && (
        <p role="status" className="text-body text-fg-sub">
          이번 주 동네 상태를 불러오고 있어요
        </p>
      )}
      {load.status === 'failed' && (
        <AlertBox
          tone="danger"
          action={
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" size="sm" onClick={load.retry}>
                다시 시도
              </Button>
              {pickOnFailure && (
                <Button variant="secondary" size="sm" onClick={onPickRegion}>
                  동네 고르기
                </Button>
              )}
            </div>
          }
        >
          이번 주 동네 상태를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </AlertBox>
      )}
      {load.status === 'no-region' && (
        // 하이드레이션 · 읽기 뒤 새로 나타나는 상자라 status 영역이면 첫 알림을 놓칠 수 있다(AlertBox 주석)
        <AlertBox
          tone="info"
          role="alert"
          action={
            <Button variant="secondary" size="sm" className="self-start" onClick={onPickRegion}>
              동네 고르기
            </Button>
          }
        >
          동네를 고르면 이번 주 동네 상태를 보여 드려요.
        </AlertBox>
      )}
    </div>
  )
}
