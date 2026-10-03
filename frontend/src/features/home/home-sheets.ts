'use client'

import { lazyComponent } from '@/lib/lazy-component'

/*
 * 홈 위에 뜨는 시트(지연 로드, #184 — `src/lib/lazy-component.ts`). 첫 로드 HTML · 초기 스크립트에는 들지 않는다.
 * 홈이 하이드레이션 뒤 유휴 시간에 넷을 미리 받고(`preloadWhenIdle`), 그 전에 열면 열 때 받는다.
 * 서버 그림에는 늘 없다. 전에도 닫힌 시트만 서버가 그렸고(`dialog` 는 effect 의 `showModal()` 로 열린다) 첫 그림에 보이지 않았다.
 */
export const loginSheet = lazyComponent(() =>
  import('@/features/auth/login-sheet').then((mod) => mod.LoginSheet),
)
export const healthConsentSheet = lazyComponent(() =>
  import('@/features/auth/health-consent-sheet').then((mod) => mod.HealthConsentSheet),
)
export const reportFlow = lazyComponent(() =>
  import('@/features/report/report-flow').then((mod) => mod.ReportFlow),
)
export const explainSheet = lazyComponent(() =>
  import('./explain-sheet').then((mod) => mod.ExplainSheet),
)

/** 유휴 시간에 미리 받을 시트 */
export const HOME_SHEETS = [loginSheet, healthConsentSheet, reportFlow, explainSheet] as const
