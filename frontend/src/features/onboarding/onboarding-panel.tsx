import type { ReactNode } from 'react'
import Image from 'next/image'

/**
 * 첫 진입 일러스트(밤 동네 그림) + 어두운 덮개. 부모를 꽉 채운다 — 부모에 `relative` 와 크기를 준다.
 *
 * Start-T · Start-D · Setup-1/2/3-D 의 그림은 모두 같아 `public/onboarding/neighborhood.svg` 하나로 뽑았다.
 * 시안의 `preserveAspectRatio="xMidYMid slice"` 는 `object-cover` 로 옮긴다. 장식이라 `alt=""` 다.
 * SVG 라 최적화할 것이 없어 `unoptimized` 로 그대로 내려보낸다.
 *
 * 데스크톱 · 태블릿 시작 화면에서 가장 큰 요소(LCP)라 미루지 않고 바로 받는다(`loading="eager"`).
 * 모바일은 그림을 숨기지만 압축하면 몇 KB 라 같이 받아도 부담이 작다.
 */
export function OnboardingIllustration() {
  return (
    <>
      <Image
        src="/onboarding/neighborhood.svg"
        alt=""
        fill
        unoptimized
        loading="eager"
        className="object-cover"
      />
      <div className="absolute inset-0 bg-image-cover" />
    </>
  )
}

/**
 * 데스크톱 오른쪽 일러스트 패널 (Start-D · Setup-1/2/3-D). 아래쪽에 출처 칩과 화면마다 다른 큰 문구를 둔다.
 *
 * 왼쪽 콘텐츠와 같은 말을 그림 위에 다시 적은 장식이라 보조기술에서 숨긴다(시안 `aria-hidden`).
 * 데스크톱에서만 보인다.
 */
export function OnboardingSidePanel({ title }: { title: ReactNode }) {
  return (
    <section aria-hidden="true" className="relative hidden grow overflow-hidden desktop:block">
      <OnboardingIllustration />
      <div className="absolute inset-x-14 bottom-14 flex flex-col gap-3">
        <span className="self-start rounded-chip border-hairline border-on-image-line px-2.5 py-1 text-sub font-semibold text-bg">
          시민 자가보고 · 행정동 단위
        </span>
        <span className="text-hero-title leading-[1.35] font-bold text-bg">{title}</span>
      </div>
    </section>
  )
}
