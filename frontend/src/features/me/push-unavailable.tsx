'use client'

import Link from 'next/link'
import { useSearchParams } from 'next/navigation'

import { INSTALL_PATH, installSearch } from '@/features/install/install-entry'
import { navHref } from '@/lib/nav'
import type { PushSupport } from '@/lib/push-support'
import { useDevicePlatform } from '@/lib/use-push-support'

/**
 * 내 정보 알림 섹션의 미지원 안내 (Settings-nopush · -T · -D). 회색 상자 · 14 · 줄 높이 1.55.
 *
 * | 푸시 | 상자 |
 * | --- | --- |
 * | `needs-install` iPhone | "이 기기에서는 알림을 받을 수 없어요" + iPhone 공유 버튼 안내 (Settings-nopush) |
 * | `needs-install` iPad | "이 브라우저에서는 알림을 받을 수 없어요" + 앱으로 설치 안내 (Settings-nopush-T) |
 * | `unsupported` | "이 브라우저에서는 알림을 받을 수 없어요" + 홈 상단 안내만 (설치해도 받지 못해 설치 문장은 뺀다) |
 *
 * 홈 화면에 추가하면 받을 수 있는 기기(`needs-install`)는 상자 안에 설치 안내(S12 `/install`)로 가는 링크를 둔다.
 * 지원되거나 아직 모르면(서버 · 하이드레이션 첫 그림) 그리지 않는다.
 *
 * **알림을 켤 수 없는 때(`canEnable` false — 실데이터, 알림 설정 API 가 없음, #195)는 굵은 줄과 "같은 내용은 홈 상단에서 확인할 수
 * 있어요." 만 둔다.** 설치하면 켤 수 있다는 문장 · 설치 안내 링크를 빼 알림 설정 화면의 "지금은 켜고 끌 수 없어요" 와 다른 말을 하지 않는다.
 */
export function PushUnavailable({
  support,
  regionCode,
  canEnable,
}: {
  support: PushSupport | null
  /** 이 서비스에서 지금 알림을 켤 수 있는지(목데이터). false 면 설치 문장 · 링크를 뺀다 */
  canEnable: boolean
  /** 둘러보기 동네. 설치 안내에서 주소로 바로 닫을 때 홈 주소에 남긴다 */
  regionCode: string | null
}) {
  const searchParams = useSearchParams()
  const platform = useDevicePlatform()
  if (support !== 'needs-install' && support !== 'unsupported') return null

  const ipad = platform === 'ipad'
  const deviceWord = support === 'needs-install' && !ipad ? '기기' : '브라우저'
  const installable = support === 'needs-install' && canEnable
  const howTo = !installable
    ? null
    : ipad
      ? '앱으로 설치하면 알림을 켤 수 있어요.'
      : 'iPhone은 Safari 공유 버튼 → 홈 화면에 추가 후 알림을 켤 수 있어요.'

  return (
    <div
      role="status"
      className="mt-2 mb-1 rounded-button bg-section px-4 py-3.5 text-body-strong leading-[1.55] text-fg tablet:mb-2"
    >
      <strong className="mb-1 block">이 {deviceWord}에서는 알림을 받을 수 없어요</strong>
      같은 내용은 홈 상단에서 확인할 수 있어요.{howTo && ` ${howTo}`}
      {installable && (
        <Link
          href={navHref(INSTALL_PATH, installSearch(regionCode, searchParams))}
          className="mt-1 flex min-h-touch w-fit items-center font-semibold text-brand"
        >
          홈 화면에 추가하는 방법 보기
        </Link>
      )}
    </div>
  )
}
