'use client'

import { useEffect } from 'react'

import { startCurrentReport } from '@/features/report/current-report'
import { readBrowserDataSource } from '@/lib/data-source'
import { restoreSession, revalidateSession, startSession } from '@/lib/session/session-store'
import { useDataSource } from '@/lib/use-data-source'

import { startMemberInfo } from './member-info'

/**
 * 세션 저장소를 켠다. 루트 레이아웃(`app/layout.tsx`) body 안, 화면(children)보다 앞 형제로 하나만 둔다 —
 * 형제 effect 는 앞에서부터 돌아 화면의 effect 보다 먼저 슬롯을 끼우고 복원을 시작한다.
 *
 * - API 계층에 access token 공급자 · 갈아 끼우기를 끼우고 다른 탭 소식을 받는다(`startSession`). 출처와 무관하게 늘 켠다 —
 *   목데이터 모드에서는 세션이 비어 있어 아무 토큰도 주지 않는다. 해제하면 슬롯을 비운다
 * - 실데이터 모드면 세션을 되살린다(`restoreSession`, 여러 번 불러도 한 번). 힌트 쿠키가 있을 때만 재발급을 한 번 한다.
 *   출처는 훅 값이 아니라 **쿠키를 직접 읽는다**(`readBrowserDataSource`). 하이드레이션 커밋의 `useDataSource()` 는 서버 기본값이라,
 *   그 값을 기다리면 다시 그릴 때까지 `idle` 이 이어지고 그 사이 나간 회원 요청이 토큰 없이 나간다. effect 라 그리는 값은
 *   바뀌지 않아 하이드레이션이 어긋나지 않는다. 훅 값은 토글로 출처가 바뀔 때 다시 확인하는 신호로만 쓴다
 * - 회원 정보 저장소(`member-info.ts`)를 세션에 잇는다(`startMemberInfo`). 세션이 회원이 되면 내 정보 · 내 동네를 읽고 비회원이
 *   되면 지운다. 목데이터 모드는 세션이 회원이 되지 않아 요청하지 않는다
 * - 이번 주 보고 저장소(`features/report/current-report.ts`, #165)도 같이 잇는다. 보고할 수 있는 회원(`reportWritable`)이 되면
 *   이번 주 보고를 읽고, 아니게 되면 지운다
 * - 화면이 뒤로 가기 캐시(bfcache)에서 되살아나면(`pageshow` 의 `persisted`, #186) 실데이터 모드의 회원 세션을 다시 확인한다
 *   (`revalidateSession`). 확인하는 동안 `restoring` 으로 가려 회원 UI · 회원 정보 · 이번 주 보고가 보이지 않고, 실패하면 비회원이다 —
 *   얼어 있던 동안 다른 탭 · 기기에서 로그아웃했으면 앞 회원의 화면이 다시 보이지 않게 한다. 출처는 복원 때와 같이 쿠키를 직접 읽는다.
 *   목데이터 모드는 그대로다(목 세션을 건드리지 않는다)
 */
export function SessionBootstrap(): null {
  const source = useDataSource()

  useEffect(() => startSession(), [])

  useEffect(() => startMemberInfo(), [])

  useEffect(() => startCurrentReport(), [])

  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted && readBrowserDataSource() === 'api') void revalidateSession()
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  useEffect(() => {
    if (readBrowserDataSource() === 'api') void restoreSession()
  }, [source])

  return null
}
