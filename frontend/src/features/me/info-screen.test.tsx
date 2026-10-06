// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import { HealthConsentNotice } from '@/features/auth/health-consent'
import { PRIVACY_CONSENT_DETAIL } from '@/features/auth/terms-screen'
import { AI_DRAFT_DISCLOSURE } from '@/features/notice/notice-screen'
import { NavTrailProvider } from '@/lib/use-nav-trail'

import type { ConfirmKind } from './confirm'
import { ConfirmDialog } from './confirm-dialog'
import { INFO_PAGE_KINDS, INFO_PAGES, INFO_PATHS, type InfoPageKind } from './info-pages'
import { InfoScreen } from './info-screen'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/me/privacy'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

// 앱에서는 루트 레이아웃의 NavTrailProvider(앱 안 이동 기록)가 감싼다
function withTrail(children: ReactNode) {
  return <NavTrailProvider>{children}</NavTrailProvider>
}

function renderInfo(kind: InfoPageKind, regionCode?: string) {
  pathname = INFO_PATHS[kind]
  return render(
    withTrail(<InfoScreen kind={kind} regionName="○○동" {...(regionCode ? { regionCode } : {})} />),
  )
}

/** 섹션 제목 아래 목록의 글자 */
function itemsUnder(title: string): string[] {
  const section = screen.getByRole('heading', { level: 2, name: title }).closest('section')
  if (!section) throw new Error(`${title} 섹션이 없다`)
  return within(section)
    .getAllByRole('listitem')
    .map((item) => item.textContent ?? '')
}

/** 건강정보 동의 고지(Setup-4 · 동의 시트)의 한 항목 값 */
function consentNotice(term: string): string {
  const { unmount } = render(<HealthConsentNotice />)
  const value = screen.getByText(term).nextElementSibling?.textContent ?? ''
  unmount()
  return value
}

/** 확인 대화상자(Confirm-consent-withdraw · Confirm-withdraw)의 점 목록 */
function confirmItems(kind: ConfirmKind): string[] {
  const { container, unmount } = render(
    <ConfirmDialog
      kind={kind}
      open
      pending={false}
      failed={false}
      onConfirm={() => {}}
      onClose={() => {}}
    />,
  )
  const items = [...container.querySelectorAll('li')].map((li) => li.textContent ?? '')
  unmount()
  return items
}

const privacySection = (title: string) => {
  const section = INFO_PAGES.privacy.sections.find((s) => s.title === title)
  if (!section) throw new Error(`${title} 섹션이 없다`)
  return section
}

const allText = (kind: InfoPageKind) => {
  const page = INFO_PAGES[kind]
  return [
    page.title,
    page.lead,
    ...page.sections.flatMap((s) => [s.title, ...s.items, ...(s.notes ?? [])]),
  ]
}

beforeEach(() => {
  search = ''
  resetMockSession()
  vi.clearAllMocks()
})

describe('InfoScreen 안내 화면 틀', () => {
  it.each(INFO_PAGE_KINDS)('%s: 제목 · 섹션 제목 · 행을 그린다', (kind) => {
    search = 'mock-auth=member'
    renderInfo(kind)
    const page = INFO_PAGES[kind]

    // 모바일 · 태블릿 머리줄과 데스크톱 본문 위 — 폭마다 하나만 보인다
    expect(screen.getAllByRole('heading', { level: 1, name: page.title })).toHaveLength(2)
    expect(screen.getByText(page.lead)).toBeDefined()
    for (const section of page.sections) {
      expect(itemsUnder(section.title)).toEqual(section.items)
      for (const note of section.notes ?? []) expect(screen.getByText(note)).toBeDefined()
    }
  })

  it('비회원도 본다 — 로그인으로 보내지 않고 본문을 그린다', async () => {
    renderInfo('privacy')
    await act(async () => {})

    expect(screen.getByRole('heading', { level: 2, name: '모으지 않는 것' })).toBeDefined()
    expect(router.replace).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })

  it('회원: 알림(종)이 있고 알림 설정으로 간다. 주소로 바로 들어왔으면 뒤로가 동네 · 덮어쓰기를 남긴 내 정보로 간다', async () => {
    search = 'region=11440660&mock-auth=member&mock-provider=email&other=1'
    renderInfo('data-sources', '11440660')
    const user = userEvent.setup()

    await user.click(screen.getAllByRole('button', { name: '알림 설정' })[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(
      '/me/notifications?region=11440660&mock-auth=member&mock-provider=email',
    )

    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith(
      '/me?region=11440660&mock-auth=member&mock-provider=email',
    )
    // 데스크톱 돌아가기는 내 정보다
    expect(screen.getByRole('button', { name: '내 정보로 돌아가기' })).toBeDefined()
  })

  it('비회원: 알림(종)이 없고, 뒤로는 동네를 남긴 홈이며 데스크톱 돌아가기는 "뒤로" 다', async () => {
    search = 'region=11440660'
    renderInfo('ai', '11440660')
    const user = userEvent.setup()

    expect(screen.queryByRole('button', { name: '알림 설정' })).toBeNull()
    expect(screen.queryByRole('button', { name: '내 정보로 돌아가기' })).toBeNull()

    await user.click(screen.getByRole('button', { name: '뒤로 가기' }))
    expect(router.replace).toHaveBeenCalledWith('/?region=11440660')
  })

  it('비회원의 머리줄 보고 버튼은 보고하려던 로그인으로 간다', async () => {
    renderInfo('ai', '11440660')
    const reports = screen.getAllByRole('button', { name: '로그인하고 보고하기' })
    await userEvent.setup().click(reports[0] as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(expect.stringMatching(/^\/login\?.*intent=report/))
  })

  it('앱 안에서 왔으면 어느 화면에서 왔든 기록을 되돌린다', async () => {
    pathname = '/'
    const { rerender } = render(withTrail(<div />))
    pathname = INFO_PATHS.privacy
    rerender(withTrail(<InfoScreen kind="privacy" regionName="○○동" />))

    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('INFO_PAGES 문구 (이미 보여 준 동의 문구와 어긋나지 않는다)', () => {
  it('모으는 것 · 모으지 않는 것은 건강정보 동의 고지의 항목과 같다', () => {
    const privacy = INFO_PAGES.privacy.sections
    const section = (title: string) => privacy.find((s) => s.title === title)?.items
    expect(section('증상을 보고할 때 모으는 것')).toEqual(consentNotice('모으는 것').split(', '))
    expect(section('모으지 않는 것')).toEqual(consentNotice('모으지 않는 것').split(', '))
  })

  it('가입할 때 모으는 것은 가입 동의 `개인정보 수집·이용` 항목을 모두 담는다 (Setup-3)', () => {
    const consent = PRIVACY_CONSENT_DETAIL.split(', ')
    const items = privacySection('가입할 때 모으는 것').items
    expect(consent).toEqual(['이메일', '닉네임', '행정동'])
    consent.forEach((term, index) => expect(items[index]).toContain(term))
  })

  it('철회 · 탈퇴 안내는 확인 대화상자의 사실과 같다', () => {
    const items = privacySection('동의를 철회하거나 탈퇴하면').items
    const withdrawConsent = confirmItems('consent-withdraw')
    const withdraw = confirmItems('withdraw')

    // 철회: 보낸 보고를 모두 지움 · 다시 동의하기 전까지 보고 불가 · 모든 기기 로그아웃
    expect(withdrawConsent).toContain('지금까지 보낸 보고를 모두 지워요.')
    expect(items[0]).toContain('보낸 보고를 모두')
    expect(withdrawConsent).toContain('다시 동의하기 전까지 보고할 수 없어요.')
    expect(items).toContain('다시 동의하기 전까지 보고할 수 없어요.')
    expect(withdrawConsent).toContain('모든 기기에서 로그아웃돼요.')
    expect(items.some((item) => item.endsWith('모든 기기에서 로그아웃돼요.'))).toBe(true)
    // 탈퇴: 보고 바로 지움 · 계정 30일 뒤
    expect(withdraw).toEqual(['보낸 보고는 바로 지워요.', '계정은 30일 뒤 완전히 지워요.'])
    expect(items).toContain('회원 탈퇴하면 보낸 보고를 바로 지워요.')
    expect(items).toContain('계정은 탈퇴 30일 뒤 완전히 지워요.')
  })

  it('AI 사용 방식은 동네 안내의 AI 고지와 같은 사실이다 (S07)', () => {
    const [drafted, notDiagnosis, seeDoctor] = AI_DRAFT_DISCLOSURE.split(/(?<=\.) /)
    const ai = allText('ai')
    expect(drafted).toBe('안내문 초안은 AI가 쓰고 운영자가 검토해 발행해요.')
    expect(ai.some((line) => line.includes('안내문 초안을 써요'))).toBe(true)
    expect(ai.some((line) => line.includes('운영자가 초안을 검토'))).toBe(true)
    expect(ai.some((line) => line.includes(notDiagnosis ?? '없음'))).toBe(true)
    expect(ai).toContain(seeDoctor)
  })

  it('개별 보고 보관 기간은 동의 고지와 같은 52주다', () => {
    expect(consentNotice('보관 기간')).toContain('52주')
    expect(allText('privacy').some((line) => line.includes('52주 뒤 지워요'))).toBe(true)
  })

  it.each(INFO_PAGE_KINDS)(
    '%s: 불안을 키우는 단어를 쓰지 않고 쉼표로 두 문장을 잇지 않는다 (design-guide 문구 규칙)',
    (kind) => {
      for (const line of allText(kind)) {
        expect(line).not.toMatch(/위험|비상/)
        // "유행" 은 공식 판단을 가리킬 때만 쓴다
        expect(line.replaceAll('공식 유행', '')).not.toContain('유행')
        expect(line).not.toMatch(/요,|고,|며,|지,/)
      }
    },
  )

  it.each(INFO_PAGE_KINDS)('%s: 목록 행 · 덧붙임은 한 줄에 한 문장이다', (kind) => {
    for (const section of INFO_PAGES[kind].sections) {
      for (const line of [...section.items, ...(section.notes ?? [])]) {
        expect(line.match(/[.?!](\s|$)/g)?.length ?? 0).toBeLessThanOrEqual(1)
      }
    }
  })
})
