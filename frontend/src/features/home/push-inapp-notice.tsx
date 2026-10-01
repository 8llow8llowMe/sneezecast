'use client'

import { Callout } from '@/components/callout'
import { InfoIcon } from '@/components/icons'
import { usePushSupport } from '@/lib/use-push-support'

import type { HomeWeekly } from './types'

/**
 * 알림 대신 홈 상단에 띄우는 안내 (State-push-inapp · -T · -D). 회색 `Callout`.
 *
 * **회원이고, 운영자가 이번 주 동네 안내를 발행했고, 이 기기에서 푸시를 받을 수 없을 때만** 보인다 —
 * 미지원 환경(iOS 홈 화면 추가 전 등)에서도 알림과 같은 내용을 서비스 안에서 볼 수 있어야 한다(루트 CLAUDE.md "알림").
 * 푸시 지원을 모르는 첫 그림(서버 · 하이드레이션)에는 그리지 않는다.
 *
 * 모바일은 주차 줄 아래(좌우 20 · 위 12), 태블릿 · 데스크톱은 공식 정보 행 아래(좌우 24 · 32)다.
 */
export function PushInappNotice({ week, signedIn }: { week: HomeWeekly; signedIn: boolean }) {
  const push = usePushSupport()
  const unavailable = push === 'needs-install' || push === 'unsupported'
  if (!signedIn || week.notice === null || !unavailable) return null

  return (
    <Callout
      tone="neutral"
      icon={<InfoIcon className="shrink-0 text-fg-sub" />}
      className="mx-5 mt-3 tablet:mx-6 desktop:mx-8"
    >
      {week.regionName} 이번 주 안내가 발행됐어요 · 알림 대신 여기서 알려드려요
    </Callout>
  )
}
