'use client'

import { useRouter } from 'next/navigation'

import clsx from 'clsx'

import { Button } from '@/components/button'

import { OnboardingIllustration, OnboardingSidePanel } from './onboarding-panel'
import { BROWSE_REGION_PATH, SETUP_REGION_PATH } from './paths'

const FEATURES = [
  { term: '10초 보고', description: '이번 주 건강 상태만 골라요' },
  { term: '충분할 때만', description: '보고가 모인 동네만 변화를 보여드려요' },
  { term: '참고 정보', description: '진단이나 유행 선언이 아니에요' },
] as const

/**
 * S01 시작. 폭에 따라 구성이 바뀐다.
 *
 * | 폭 | 구성 |
 * | --- | --- |
 * | 모바일 | 서비스명 · 제목 · 설명 → 게이지 그림 → 소개 3줄 → 아래 고정 버튼 (Start) |
 * | 태블릿 | 위 560 일러스트 위에 서비스명 · 흰 제목 → 소개 3줄 → 아래 버튼 · 안내 한 줄 (Start-T) |
 * | 데스크톱 | 왼쪽 620 콘텐츠 + 오른쪽 일러스트 패널 (Start-D) |
 *
 * 로그인 단계는 시안을 기다리는 중이다. 시안이 오면 "시작하기" 와 동네 선택 사이에 끼운다 (paths.ts).
 */
export function StartScreen() {
  const router = useRouter()

  return (
    <div className="flex min-h-dvh">
      <div className="flex w-full grow flex-col desktop:w-155 desktop:shrink-0 desktop:grow-0 desktop:px-18 desktop:pt-14 desktop:pb-12">
        <main className="flex grow flex-col px-6 pt-14 tablet:grow-0 tablet:p-0">
          <div className="relative flex flex-col tablet:h-140 tablet:overflow-hidden tablet:px-12 tablet:pt-10 tablet:pb-12 desktop:h-auto desktop:overflow-visible desktop:p-0">
            {/* next/image 의 fill 은 가장 가까운 부모가 자리 기준이어야 해서 감싸는 요소를 absolute 로 둔다 */}
            <div className="absolute inset-0 hidden tablet:block desktop:hidden">
              <OnboardingIllustration />
            </div>
            <span className="relative text-section-title font-extrabold tracking-brand text-brand tablet:text-screen-title tablet:text-bg desktop:text-brand">
              우리동네체온계
            </span>
            <h1 className="relative mt-6 text-start-title leading-[1.4] font-bold text-fg tablet:mt-auto tablet:text-start-title-tablet tablet:text-bg desktop:mt-12 desktop:text-hero-title desktop:text-fg">
              요즘 우리 동네에
              <br />
              뭐가 돌고 있을까요?
            </h1>
            <p className="relative mt-3 text-section-title leading-[1.55] text-fg-sub tablet:mt-3.5 tablet:text-lead tablet:leading-[1.6] tablet:text-on-image-sub desktop:mt-4 desktop:text-fg-sub">
              일주일에 한 번 10초만 보고하면
              <br className="tablet:hidden" /> 같이 알 수 있어요.
            </p>
          </div>

          <div className="mt-9 mb-7 self-center tablet:hidden">
            <StartGauge />
          </div>

          <dl className="flex flex-col tablet:px-12 tablet:pt-8 desktop:mt-10 desktop:p-0">
            {FEATURES.map((feature, index) => (
              <div
                key={feature.term}
                className={clsx(
                  'flex gap-3.5 border-divider py-3 tablet:gap-4 tablet:border-b tablet:py-3.5',
                  index < FEATURES.length - 1 && 'border-b',
                )}
              >
                <dt className="w-22 shrink-0 text-body font-bold text-brand tablet:w-25 tablet:text-body-large">
                  {feature.term}
                </dt>
                <dd className="text-body leading-normal text-fg tablet:text-body-large">
                  {feature.description}
                </dd>
              </div>
            ))}
          </dl>
        </main>

        <div className="sticky bottom-0 flex flex-col gap-2.5 bg-bg px-page-mobile pt-3 pb-sheet tablet:static tablet:mt-auto tablet:gap-2 tablet:px-12 tablet:pt-8 tablet:pb-10 desktop:px-0 desktop:pt-10 desktop:pb-0">
          <Button fullWidth onClick={() => router.push(SETUP_REGION_PATH)}>
            시작하기
          </Button>
          <Button variant="subtle" onClick={() => router.push(BROWSE_REGION_PATH)}>
            보고 없이 둘러보기
          </Button>
          <p className="mt-1 hidden text-center text-sub text-fg-sub tablet:block">
            성인 본인만 보고할 수 있어요 · 진단이 아닌 참고 정보예요
          </p>
        </div>
      </div>

      <OnboardingSidePanel
        title={
          <>
            이번 주 우리 동네는
            <br />
            괜찮을까요?
          </>
        }
      />
    </div>
  )
}

/** 모바일 시작 화면의 3구간 게이지 그림 (Start). 모든 구간을 진하게, 점은 가운데 위에 둔다. 장식이다 */
function StartGauge() {
  return (
    <svg width="220" height="128" viewBox="0 0 120 70" fill="none" aria-hidden="true">
      <path d="M12 62A48 48 0 0 1 36 20.43" strokeWidth={10} className="stroke-status-normal" />
      <path
        d="M37.5 19.6A48 48 0 0 1 82.5 19.6"
        strokeWidth={10}
        className="stroke-status-slight"
      />
      <path d="M84 20.43A48 48 0 0 1 108 62" strokeWidth={10} className="stroke-status-high" />
      <circle cx="60" cy="14" r="8" strokeWidth={3} className="fill-bg stroke-brand" />
    </svg>
  )
}
