'use client'

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'

import { Button } from '@/components/button'
import { Modal } from '@/components/modal'
import { TextField } from '@/components/text-field'
import { ToastRegion, useToast } from '@/components/toast'
import { APP_NAME } from '@/lib/app-info'

import { SHARE_TEXT, SHARE_TITLE } from './share-link'

// 브라우저가 공유 기능을 가졌는지는 열린 동안 바뀌지 않는다고 본다
const noopSubscribe = () => () => {}
const clientCanShare = () => typeof navigator.share === 'function'
const serverCanShare = () => false

/**
 * 이 브라우저에 공유 기능(`navigator.share`)이 있는지. 서버는 브라우저를 몰라 **서버 · 하이드레이션 첫 그림은 false** 이고,
 * 그 뒤 다시 그릴 때 브라우저 값으로 바뀐다 — 서버 그림과 어긋나지 않게 한다.
 */
function useCanShare(): boolean {
  return useSyncExternalStore(noopSubscribe, clientCanShare, serverCanShare)
}

/** 클립보드에 쓴다. 클립보드가 없거나(보안 연결이 아님 · 오래된 브라우저) 권한이 막히면 false */
async function writeClipboard(text: string): Promise<boolean> {
  // lib.dom 은 늘 있다고 적지만, 보안 연결이 아니면 navigator.clipboard 가 없다
  const clipboard = navigator.clipboard as Clipboard | undefined
  if (typeof clipboard?.writeText !== 'function') return false
  try {
    await clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

/** 복사하지 못했을 때 링크 칸 아래 설명 */
const COPY_FALLBACK = '복사하지 못했어요. 링크를 길게 눌러 복사해 주세요.'

/**
 * 보고 완료(S06)의 `우리 동네 자료 함께 채우기` 공유 시트 (#151, Flow 시안 152~166행). 모바일 바텀시트, 태블릿 · 데스크톱 가운데 대화상자(`Modal`).
 *
 * - 본문은 시안의 미리보기 카드(앱 이름 · `우리 동네 건강을 같이 살펴요` · 안내)와 `내 보고 내용은 공유되지 않아요` 다
 * - `메신저로 보내기`(주): 기기 공유 창(`navigator.share`)으로 보낸다. 카카오톡 SDK 공유는 #61(카카오 연동) 뒤에 이 버튼으로 붙인다.
 *   브라우저에 공유 기능이 없으면 그리지 않고 `링크 복사` 를 주 버튼으로 올린다(하이드레이션 뒤 판단). 사용자가 취소하면 알리지 않는다
 * - `링크 복사`(보조): 클립보드에 쓰고 `링크를 복사했어요` 를 띄운다. 시안에 링크 칸이 없어 평소엔 링크를 보이지 않고,
 *   쓰지 못했을 때만 링크 칸을 보여 골라 두고 칸 아래에 직접 복사하라고 안내한다
 *
 * 열림 상태는 보고 흐름의 단계(`?report=share`)로 부모가 갖는다(`report-flow.tsx`).
 */
export function ShareSheet({
  open,
  onClose,
  link,
}: {
  open: boolean
  onClose: () => void
  /** 공유할 링크 (`buildShareLink`) */
  link: string
}) {
  const canShare = useCanShare()
  const { toast, show, dismiss } = useToast()
  // 복사에 실패한 횟수. 0 이면 링크 칸이 없다. 실패할 때마다 늘려 다시 실패해도 칸을 다시 골라 둔다
  const [copyFailures, setCopyFailures] = useState(0)
  const [sharing, setSharing] = useState(false)
  const field = useRef<HTMLInputElement>(null)

  // 링크 칸은 실패한 뒤에야 그려져 그린 다음에 골라 둔다. 칸 아래 안내는 칸의 설명이라 포커스를 받을 때 함께 읽힌다
  useEffect(() => {
    if (copyFailures === 0) return
    field.current?.focus()
    field.current?.select()
  }, [copyFailures])

  async function copy() {
    if (await writeClipboard(link)) {
      setCopyFailures(0)
      show({ message: '링크를 복사했어요' })
      return
    }
    dismiss()
    setCopyFailures((count) => count + 1)
  }

  async function share() {
    setSharing(true)
    try {
      await navigator.share({ title: SHARE_TITLE, text: SHARE_TEXT, url: link })
    } catch (error) {
      // 공유 창을 닫은 것(취소)은 실패가 아니다
      if (!isAbort(error)) show({ message: '메신저로 보내지 못했어요. 링크 복사를 눌러 주세요.' })
    } finally {
      setSharing(false)
    }
  }

  function close() {
    dismiss()
    setCopyFailures(0)
    onClose()
  }

  return (
    <Modal open={open} onClose={close} title="이렇게 공유돼요">
      {/* 받는 사람에게 보일 모습 (시안의 미리보기 카드) */}
      <div className="flex flex-col gap-2.5 rounded-card border-hairline border-divider p-5">
        <span className="text-sub font-extrabold tracking-brand text-brand">{APP_NAME}</span>
        <span className="text-screen-title leading-[1.4] font-bold text-fg">
          우리 동네 건강을
          <br /> 같이 살펴요
        </span>
        <span className="text-sub leading-normal text-fg-sub">
          일주일에 한 번 10초면 돼요. 이름·주소·위치는 받지 않아요.
        </span>
      </div>
      <p className="text-caption text-fg-sub">내 보고 내용은 공유되지 않아요</p>
      {copyFailures > 0 && (
        <TextField
          ref={field}
          label="공유 링크"
          value={link}
          readOnly
          onFocus={(event) => event.currentTarget.select()}
          hint={COPY_FALLBACK}
        />
      )}
      {/* 한 줄에 둘이면 폭을 반씩 나눈다 — Button 은 기본이 shrink-0 이다 */}
      <div className="flex gap-2.5">
        <Button
          variant={canShare ? 'secondary' : 'primary'}
          fullWidth
          className="flex-1"
          onClick={() => void copy()}
        >
          링크 복사
        </Button>
        {canShare && (
          <Button fullWidth className="flex-1" disabled={sharing} onClick={() => void share()}>
            메신저로 보내기
          </Button>
        )}
      </div>
      <ToastRegion toast={toast} onAction={dismiss} />
    </Modal>
  )
}
