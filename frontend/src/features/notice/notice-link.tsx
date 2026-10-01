'use client'

import type { ComponentProps, MouseEvent } from 'react'
import Link from 'next/link'

import { markNoticeEntry } from './notice-entry'

type NoticeLinkProps = Omit<ComponentProps<typeof Link>, 'href'> & { href: string }

/**
 * 동네 안내(S07)로 가는 링크. 누르면 앱 안에서 들어왔다고 남겨 안내 화면의 "뒤로" 가 기록을 되돌리게 한다(`notice-entry.ts`).
 * 새 탭 · 새 창으로 여는 누름(보조 키 · 가운데 버튼)은 이 문서에서 이동하지 않으므로 남기지 않는다.
 */
export function NoticeLink({ href, onClick, ...rest }: NoticeLinkProps) {
  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event)
    const plain =
      event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey
    if (!event.defaultPrevented && plain) {
      markNoticeEntry(new URL(href, 'http://localhost').pathname)
    }
  }
  return <Link href={href} onClick={handleClick} {...rest} />
}
