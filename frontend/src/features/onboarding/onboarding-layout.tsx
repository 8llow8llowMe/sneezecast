import type { ReactNode } from 'react'

import { IconButton } from '@/components/icon-button'
import { ChevronLeftIcon } from '@/components/icons'

import { OnboardingSidePanel } from './onboarding-panel'

/** 첫 진입 단계 수. 시안은 `/ 3` 이지만 S02-4 증상 보고 동의가 더해질 예정이라 4로 센다 */
export const SETUP_STEP_COUNT = 4

export type OnboardingLayoutProps = {
  /** 몇 번째 단계인지. 없으면 단계 표시를 그리지 않는다 (둘러보기 모드) */
  step?: number | undefined
  /** 없으면 뒤로 버튼을 그리지 않는다 (가입을 마친 뒤의 증상 보고 동의 — 되돌아가 다시 가입하지 않게) */
  onBack?: (() => void) | undefined
  /** 뒤로 버튼을 꺼진 모양으로 둔다(`aria-disabled`, 포커스는 남는다). 누름은 `onBack` 이 막는다 — 보내는 중 */
  backDisabled?: boolean | undefined
  /** 데스크톱 오른쪽 패널의 큰 문구 */
  panelTitle: ReactNode
  /** 화면 아래 버튼 영역 */
  footer: ReactNode
  children: ReactNode
}

/**
 * 첫 진입 단계 화면(S02 동네 선택 · 성인 확인 · 동의)의 틀. 폭에 따라 구성이 바뀐다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 머리줄 44(뒤로 · 단계, 위 6) → 본문 → 아래 고정 버튼 영역 |
 * | 태블릿 | 회색 바탕 가운데 흰 카드 560. 카드 안 머리줄(뒤로 · 서비스명 · 단계) → 본문 → 버튼, 간격 20 |
 * | 데스크톱 | 왼쪽 620 콘텐츠(서비스명 → 머리줄 → 본문 → 아래 버튼, 간격 20) + 오른쪽 일러스트 패널 |
 *
 * 시안: `docs/design/auth/screens/` 의 Setup-1 · Setup-1-empty · Setup-1-browse · Setup-2 (+ -T · -D).
 * 본문 안 간격은 화면이 정한다.
 */
export function OnboardingLayout({
  step,
  onBack,
  backDisabled,
  panelTitle,
  footer,
  children,
}: OnboardingLayoutProps) {
  return (
    <div className="flex min-h-dvh tablet:items-center tablet:justify-center tablet:bg-section tablet:p-12 desktop:items-stretch desktop:justify-start desktop:bg-bg desktop:p-0">
      <div className="flex w-full grow flex-col bg-bg tablet:max-w-140 tablet:grow-0 tablet:rounded-dialog tablet:px-10 tablet:pt-8 tablet:pb-10 desktop:w-155 desktop:max-w-none desktop:shrink-0 desktop:rounded-none desktop:px-18 desktop:pt-12 desktop:pb-11">
        <span className="hidden text-screen-title font-extrabold tracking-brand text-brand desktop:block">
          우리동네체온계
        </span>

        <header className="mt-1.5 flex h-11 shrink-0 items-center gap-1 px-page-mobile tablet:mt-0 tablet:px-0 desktop:mt-5">
          {onBack ? (
            <IconButton
              label="뒤로"
              icon={<ChevronLeftIcon />}
              onClick={onBack}
              aria-disabled={backDisabled || undefined}
              className="-ml-3"
            />
          ) : (
            // 뒤로 버튼이 없어도 머리줄 높이(44)는 그대로 둔다
            <span className="size-touch" aria-hidden="true" />
          )}
          <span className="hidden text-section-title font-extrabold tracking-brand text-brand tablet:inline desktop:hidden">
            우리동네체온계
          </span>
          <span className="grow" />
          {step !== undefined && (
            <span className="text-sub font-semibold text-fg-sub">
              <span className="sr-only">단계 </span>
              {step} / {SETUP_STEP_COUNT}
            </span>
          )}
        </header>

        <main className="flex grow flex-col px-page-mobile pt-2 tablet:mt-5 tablet:grow-0 tablet:p-0 desktop:grow">
          {children}
        </main>

        {/* 데스크톱은 시안의 빈 칸(flex-grow)으로 버튼을 아래로 민다. 본문이 grow 라 버튼이 맨 아래에 붙는다 */}
        <div className="sticky bottom-0 flex flex-col gap-1.5 bg-bg px-page-mobile pt-3 pb-sheet tablet:static tablet:mt-5 tablet:p-0 desktop:mt-10">
          {footer}
        </div>
      </div>

      <OnboardingSidePanel title={panelTitle} />
    </div>
  )
}
