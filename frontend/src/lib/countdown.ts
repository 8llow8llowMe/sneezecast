'use client'

import { useEffect, useState } from 'react'

/**
 * `deadlineMs`(시각, ms)까지 남은 초. 1초마다 다시 그린다.
 *
 * 남은 시간을 1씩 빼지 않고 늘 지금 시각으로 다시 계산한다 — 탭이 백그라운드라 타이머가 늦게 돌아도 맞다.
 * 0 이 되면 멈추고, 화면을 떠나면 타이머를 정리한다. `deadlineMs` 가 null 이면 0 이다.
 * 마감이 바뀌면(다시 받기) 바로 그 렌더부터 새 마감으로 센다.
 */
export function useSecondsLeft(deadlineMs: number | null): number {
  const [now, setNow] = useState(() => Date.now())
  // 마감이 바뀐 렌더에서 예전 now 로 계산하면 남은 시간이 잠깐 부풀어 보인다(다시 받은 직후 5:00 이 5:30 으로).
  // 렌더 중에 바뀐 것을 알아채 지금 시각으로 다시 맞춘다 (React 의 "이전 값 비교" 패턴)
  const [trackedDeadline, setTrackedDeadline] = useState(deadlineMs)
  if (trackedDeadline !== deadlineMs) {
    setTrackedDeadline(deadlineMs)
    setNow(Date.now())
  }

  useEffect(() => {
    if (deadlineMs === null) return
    setNow(Date.now())
    const timer = setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (current >= deadlineMs) clearInterval(timer)
    }, 1000)
    return () => clearInterval(timer)
  }, [deadlineMs])

  return secondsLeft(deadlineMs, now)
}

export function secondsLeft(deadlineMs: number | null, nowMs: number): number {
  if (deadlineMs === null) return 0
  return Math.max(0, Math.ceil((deadlineMs - nowMs) / 1000))
}

/** 초를 "4:59" · "0:42" 꼴로 */
export function formatMinSec(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
