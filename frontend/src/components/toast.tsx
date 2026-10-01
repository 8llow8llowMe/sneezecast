'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

export type ToastMessage = {
  message: string
  /** 되돌리기처럼 알림에서 바로 할 수 있는 동작 */
  action?: { label: string; onClick: () => void }
}

/**
 * 알림 영역과 알림 (Report-done-ok 의 "증상 없음으로 보냈어요 · 되돌리기").
 *
 * **영역(`role="status"`)은 알림이 없을 때도 늘 그려 둔다.** 스크린리더는 이미 있던 live region 의
 * 내용이 바뀔 때 읽는다. 알림과 함께 영역을 새로 만들면 첫 알림을 놓친다.
 *
 * 위치는 부모가 정한다(`className`). 모바일은 하단 버튼 위에 띄우고, 태블릿 · 데스크톱은
 * 대화상자 안 내용 아래에 둔다 (시안 Report-done-ok / -D).
 */
export function ToastRegion({
  toast,
  onAction,
  className,
}: {
  toast: ToastMessage | null
  /** 동작 버튼을 누르면 동작보다 먼저 부른다. 보통 알림을 닫는다 */
  onAction?: () => void
  className?: string
}) {
  return (
    <div role="status" aria-live="polite" className={className}>
      {toast && (
        <div className="flex min-h-13 items-center justify-between gap-2 rounded-button bg-toast pr-2 pl-4 text-body text-bg">
          <span>{toast.message}</span>
          {toast.action && (
            <button
              type="button"
              onClick={() => {
                // 닫기를 먼저 한다. 동작이 "보고를 되돌렸어요" 같은 다음 알림을 띄우면 그것이 남아야 한다
                const action = toast.action
                onAction?.()
                action?.onClick()
              }}
              className="min-h-touch shrink-0 cursor-pointer px-3 text-body font-bold text-toast-action"
            >
              {toast.action.label}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * 알림 상태. `show` 로 띄우고 `durationMs` 뒤에 저절로 닫는다. 새 알림은 이전 알림을 바로 바꾼다.
 *
 * 되돌리기가 있는 알림은 읽고 누를 시간이 필요해 기본 5초로 둔다.
 */
export function useToast(durationMs = 5000) {
  const [toast, setToast] = useState<ToastMessage | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const dismiss = useCallback(() => {
    clearTimeout(timer.current)
    setToast(null)
  }, [])

  const show = useCallback(
    (next: ToastMessage) => {
      clearTimeout(timer.current)
      setToast(next)
      timer.current = setTimeout(() => setToast(null), durationMs)
    },
    [durationMs],
  )

  // 화면을 떠난 뒤 타이머가 상태를 바꾸지 않게 한다
  useEffect(() => () => clearTimeout(timer.current), [])

  return { toast, show, dismiss }
}
